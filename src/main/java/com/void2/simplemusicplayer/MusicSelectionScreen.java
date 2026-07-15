package com.void2.simplemusicplayer;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

import javax.sound.sampled.*;
import java.io.*;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class MusicPlayerw {
    private static final Logger LOGGER = LogUtils.getLogger();

    // ── Constants ───────────────────────────────────────────────────────────
    private static final int NUM_BUFFERS = 8;
    private static final int BUFFER_SAMPLES = 8192; // ~186ms @ 44.1kHz stereo

    // Shared duration cache with MusicSelectionScreen
    private static final Map<String, Float> DURATION_CACHE = new ConcurrentHashMap<>();

    // ── OpenAL State ────────────────────────────────────────────────────────
    private int alSource = -1;
    private int[] alBuffers = new int[NUM_BUFFERS];

    // ── Decoder State ───────────────────────────────────────────────────────
    private long vorbisDecoder = 0;
    private ByteBuffer fileDataBuffer = null;      // owned by vorbis
    private ShortBuffer oggDecodeBuffer = null;
    private AudioInputStream wavStream = null;

    // ── Track Information ───────────────────────────────────────────────────
    private int channels = 2;
    private int sampleRate = 44100;
    private long totalSamples = 0;
    private long samplesDecoded = 0;
    private boolean isOgg = false;

    // ── Playback State ──────────────────────────────────────────────────────
    private boolean playing = false;
    private boolean paused = false;
    private float speed = 1.0f;
    private float volume = 1.0f;
    private boolean muted = false;

    private File currentFile = null;
    private String currentUrlTitle = "";
    private String currentUrlArtist = "";
    private String currentUrlThumbnail = "";
    private String currentUrlId = "";

    private volatile boolean loading = false;
    private volatile boolean newFileReady = false;
    private volatile int urlGeneration = 0;

    // ── Playlist ────────────────────────────────────────────────────────────
    private final List<File> playlist = new ArrayList<>();
    private int playlistIndex = -1;
    private boolean shuffle = false;
    private int repeatMode = 0; // 0 = off, 1 = repeat all, 2 = repeat one

    // ── Position Caching ────────────────────────────────────────────────────
    private volatile float cachedPositionFraction = 0f;
    private volatile long positionCachedAtNanos = 0L;

    // ── URL Status ──────────────────────────────────────────────────────────
    private volatile String urlStatus = "";

    public static MusicPlayer getInstance() { return INSTANCE; }
    private MusicPlayer() {}

    // =====================================================================
    //                          PUBLIC API
    // =====================================================================

    public void play(File file) {
        stop();
        loading = true;
        int idx = playlist.indexOf(file);
        if (idx >= 0) playlistIndex = idx;

        final int gen = urlGeneration;
        final String lower = file.getName().toLowerCase(Locale.ROOT);

        new Thread(() -> loadLocalFile(file, lower, gen), "smp-local-loader").start();
    }

    public void play(String url) {
        stop();
        final int gen = urlGeneration;
        new Thread(() -> loadUrl(url, gen), "smp-url-loader").start();
    }

    public void togglePause() { if (paused) resume(); else pause(); }
    public void pause() {
        if (playing && !paused && alSource != -1) {
            AL10.alSourcePause(alSource);
            paused = true;
        }
    }
    public void resume() {
        if (playing && paused && alSource != -1) {
            AL10.alSourcePlay(alSource);
            paused = false;
        }
    }

    public void stop() {
        urlGeneration++;
        cleanup();
        resetState();
    }

    public void seek(float fraction) {
        if (!isOgg || vorbisDecoder == 0 || totalSamples <= 0) return;

        float clamped = Math.clamp(fraction, 0f, 1f);
        long target = (long) (totalSamples * clamped);

        AL10.alSourceStop(alSource);
        unqueueAllBuffers();

        STBVorbis.stb_vorbis_seek(vorbisDecoder, (int) Math.min(target, Integer.MAX_VALUE));
        samplesDecoded = target;
        cachedPositionFraction = clamped;
        positionCachedAtNanos = System.nanoTime();

        refillBuffers();
        if (!paused) AL10.alSourcePlay(alSource);
    }

    /** Must be called every client tick */
    public void update() {
        if (!playing || paused || alSource == -1) return;

        refillProcessedBuffers();
        updatePositionCache();

        if (isTrackFinished()) {
            onTrackFinished();
        }
    }

    // ── Getters ─────────────────────────────────────────────────────────────
    public boolean isPlaying()         { return playing; }
    public boolean isPaused()          { return paused; }
    public boolean isLoading()         { return loading; }
    public boolean isShuffle()         { return shuffle; }
    public int     getRepeatMode()     { return repeatMode; }
    public File    getCurrentFile()    { return currentFile; }
    public boolean isMuted()           { return muted; }
    public String  getUrlStatus()      { return urlStatus; }

    public float getPositionFraction() {
        if (totalSamples <= 0 || !playing) return cachedPositionFraction;

        float dur = getDurationSeconds();
        if (dur <= 0) return cachedPositionFraction;

        float elapsed = (System.nanoTime() - positionCachedAtNanos) * 1e-9f;
        return Math.clamp(cachedPositionFraction + elapsed * speed / dur, 0f, 1f);
    }

    public float getDurationSeconds() {
        if (currentFile != null) {
            return DURATION_CACHE.getOrDefault(currentFile.getAbsolutePath(), 0f);
        }
        return sampleRate > 0 ? (float) ((double) totalSamples / sampleRate) : 0f;
    }

    public float getPositionSeconds() {
        return getPositionFraction() * getDurationSeconds();
    }

    public String getCurrentTrackName() {
        if (currentFile != null) return MusicDirectory.getTitle(currentFile.getName());
        return currentUrlTitle.isEmpty() ? "" : currentUrlTitle;
    }

    public String getCurrentArtist() {
        return currentUrlArtist;
    }

    public String getUrlThumbnailUrl() { return currentUrlThumbnail; }
    public String getUrlDownloadedId() { return currentUrlId; }

    public boolean pollNewFileReady() {
        boolean v = newFileReady;
        newFileReady = false;
        return v;
    }

    public void toggleShuffle() { shuffle = !shuffle; }
    public void toggleRepeat()  { repeatMode = (repeatMode + 1) % 3; }

    public void setVolume(float vol) {
        volume = Math.clamp(vol, 0f, 1f);
        if (alSource != -1 && !muted)
            AL10.alSourcef(alSource, AL10.AL_GAIN, volume);
    }

    public void setMuted(boolean mute) {
        muted = mute;
        if (alSource != -1)
            AL10.alSourcef(alSource, AL10.AL_GAIN, muted ? 0f : volume);
    }

    public void setPlaylist(List<File> files) {
        playlist.clear();
        playlist.addAll(files);
        if (currentFile != null) {
            int idx = playlist.indexOf(currentFile);
            if (idx >= 0) playlistIndex = idx;
        }
    }

    public void playNext()     { advancePlaylist(true); }
    public void playPrevious() { playPreviousLogic(); }
    public void playOrResume() {
        if (playing) togglePause();
        else if (!playlist.isEmpty()) playAtIndex(Math.max(0, playlistIndex));
    }

    public void playAtIndex(int idx) {
        if (idx < 0 || idx >= playlist.size()) return;
        playlistIndex = idx;
        play(playlist.get(idx));
    }

    // =====================================================================
    //                          PRIVATE IMPLEMENTATION
    // =====================================================================

    private void loadLocalFile(File file, String lower, int gen) {
        try {
            byte[] data = Files.readAllBytes(file.toPath());
            if (!isValidGeneration(gen)) return;

            Minecraft.getInstance().execute(() -> {
                if (!isValidGeneration(gen)) return;
                try {
                    if (lower.endsWith(".ogg")) {
                        initOggFromBuffer(prepareOggBuffer(data), file);
                    } else if (lower.endsWith(".wav")) {
                        initWavBytes(data, file);
                    } else {
                        LOGGER.warn("Unsupported format: {}", file.getName());
                        loading = false;
                    }
                } catch (Exception e) {
                    LOGGER.error("Failed to initialize playback", e);
                    loading = false;
                }
            });
        } catch (Exception e) {
            LOGGER.error("Failed to read file {}", file, e);
            loading = false;
        }
    }

    private void loadUrl(String url, int gen) {
        urlStatus = "Preparing...";
        new Thread(() -> {
            try {
                String lower = url.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".ogg") || lower.endsWith(".wav")) {
                    // Direct audio file from URL
                    urlStatus = "Downloading...";
                    byte[] data = URI.create(url).toURL().openStream().readAllBytes();
                    if (!isValidGeneration(gen)) return;

                    Minecraft.getInstance().execute(() -> {
                        if (!isValidGeneration(gen)) return;
                        try {
                            if (lower.endsWith(".ogg")) {
                                initOggFromBuffer(prepareOggBuffer(data), new File("url_" + System.currentTimeMillis()));
                            } else {
                                initWavBytes(data, new File("url_" + System.currentTimeMillis()));
                            }
                        } catch (Exception e) { LOGGER.error("Direct URL playback failed", e); }
                    });
                } else {
                    downloadAndPlayViaYtDlp(url, gen);
                }
            } catch (Exception e) {
                LOGGER.error("URL loading failed", e);
                urlStatus = "Error: " + e.getMessage();
            }
        }, "smp-url-loader").start();
    }

    private void downloadAndPlayViaYtDlp(String url, int gen) {
        Path binary = YtDlpManager.get(s -> urlStatus = s);
        if (binary == null) { urlStatus = "yt-dlp unavailable"; return; }
        Path ffmpeg = FfmpegManager.get(s -> urlStatus = s);
        if (ffmpeg == null) { urlStatus = "ffmpeg unavailable"; return; }

        String id = String.format("%08x", url.hashCode() & 0xFFFFFFFFL);
        currentUrlId = id;

        Path dir = MusicDirectory.getDirectory().toPath();
        Path cachedOgg = dir.resolve(id + ".ogg");

        fetchMetadata(url, binary);

        if (Files.exists(cachedOgg)) {
            playCachedOgg(cachedOgg, gen);
            return;
        }

        // Download with retries
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                Process proc = new ProcessBuilder(
                        binary.toString(),
                        "--no-warnings",
                        "--ffmpeg-location", ffmpeg.toString(),
                        "-x", "--audio-format", "vorbis",
                        "--audio-quality", "5",
                        "-o", dir.resolve(id + ".%(ext)s").toString(),
                        "--force-overwrites",
                        url
                ).redirectErrorStream(true).start();

                String output = new String(proc.getInputStream().readAllBytes());
                if (proc.waitFor() == 0) {
                    Optional<Path> audioFile = Files.list(dir)
                            .filter(p -> p.getFileName().toString().startsWith(id + ".") &&
                                    !p.getFileName().toString().endsWith(".title"))
                            .findFirst();

                    if (audioFile.isPresent()) {
                        newFileReady = true;
                        playCachedOgg(audioFile.get(), gen);
                        return;
                    }
                }
            } catch (Exception e) {
                if (attempt == 5) LOGGER.error("yt-dlp failed", e);
                else try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
            }
        }
        urlStatus = "Download failed";
    }

    private void fetchMetadata(String url, Path binary) {
        try {
            Process p = new ProcessBuilder(
                    binary.toString(), "--no-warnings",
                    "--print", "title", "--print", "uploader", "--print", "thumbnail", url
            ).redirectErrorStream(false).start();

            String[] lines = new String(p.getInputStream().readAllBytes()).trim().split("\n");
            currentUrlTitle = lines.length > 0 ? lines[0].trim() : "";
            currentUrlArtist = lines.length > 1 ? lines[1].trim() : "";
            currentUrlThumbnail = lines.length > 2 ? lines[2].trim() : "";
        } catch (Exception e) {
            LOGGER.warn("Failed to fetch metadata", e);
        }
    }

    private void playCachedOgg(Path path, int gen) {
        try {
            byte[] data = Files.readAllBytes(path);
            ByteBuffer buf = prepareOggBuffer(data);

            Minecraft.getInstance().execute(() -> {
                if (isValidGeneration(gen)) {
                    initOggFromBuffer(buf, path.toFile());
                } else {
                    MemoryUtil.memFree(buf);
                }
            });
        } catch (Exception e) {
            LOGGER.error("Failed to play cached file", e);
        }
    }

    private ByteBuffer prepareOggBuffer(byte[] raw) {
        ByteBuffer buf = MemoryUtil.memAlloc(raw.length);
        buf.put(raw).flip();
        return buf;
    }

    private void initOggFromBuffer(ByteBuffer buffer, File file) {
        fileDataBuffer = buffer;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer err = stack.mallocInt(1);
            vorbisDecoder = STBVorbis.stb_vorbis_open_memory(buffer, err, null);
            if (vorbisDecoder == 0) {
                LOGGER.error("STBVorbis failed with error {}", err.get(0));
                MemoryUtil.memFree(buffer);
                loading = false;
                return;
            }

            STBVorbisInfo info = STBVorbisInfo.malloc(stack);
            STBVorbis.stb_vorbis_get_info(vorbisDecoder, info);
            channels = info.channels();
            sampleRate = info.sample_rate();
            totalSamples = STBVorbis.stb_vorbis_stream_length_in_samples(vorbisDecoder);
        }

        isOgg = true;
        currentFile = file;
        setupOpenAL();
        loading = false;

        // Cache duration
        DURATION_CACHE.putIfAbsent(file.getAbsolutePath(), getDurationSeconds());
    }

    private void initWavBytes(byte[] raw, File file) throws Exception {
        AudioInputStream rawStream = AudioSystem.getAudioInputStream(new ByteArrayInputStream(raw));
        AudioFormat target = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                rawStream.getFormat().getSampleRate(), 16,
                rawStream.getFormat().getChannels(),
                rawStream.getFormat().getChannels() * 2,
                rawStream.getFormat().getSampleRate(), false);

        wavStream = AudioSystem.getAudioInputStream(target, rawStream);

        channels = target.getChannels();
        sampleRate = (int) target.getSampleRate();
        totalSamples = wavStream.getFrameLength() > 0 ? wavStream.getFrameLength() : 0;

        isOgg = false;
        currentFile = file;
        setupOpenAL();
        loading = false;

        DURATION_CACHE.putIfAbsent(file.getAbsolutePath(), getDurationSeconds());
    }

    private void setupOpenAL() {
        alSource = AL10.alGenSources();
        AL10.alGenBuffers(alBuffers);

        if (isOgg) oggDecodeBuffer = MemoryUtil.memAllocShort(BUFFER_SAMPLES * channels);

        AL10.alSourcei(alSource, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
        AL10.alSource3f(alSource, AL10.AL_POSITION, 0f, 0f, 0f);
        AL10.alSourcef(alSource, AL10.AL_GAIN, muted ? 0f : volume);
        AL10.alSourcef(alSource, AL10.AL_PITCH, speed);

        int format = channels == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;

        for (int buf : alBuffers) {
            if (fillBuffer(buf, format)) {
                AL10.alSourceQueueBuffers(alSource, buf);
            }
        }

        AL10.alSourcePlay(alSource);
        playing = true;
        paused = false;
    }

    private boolean fillBuffer(int bufferId, int format) {
        return isOgg ? fillOggBuffer(bufferId, format) : fillWavBuffer(bufferId, format);
    }

    private boolean fillOggBuffer(int bufferId, int format) {
        if (vorbisDecoder == 0 || oggDecodeBuffer == null) return false;
        oggDecodeBuffer.clear();
        int decoded = STBVorbis.stb_vorbis_get_samples_short_interleaved(vorbisDecoder, channels, oggDecodeBuffer);
        if (decoded <= 0) return false;

        oggDecodeBuffer.limit(decoded * channels);
        AL10.alBufferData(bufferId, format, oggDecodeBuffer, sampleRate);
        samplesDecoded += decoded;
        return true;
    }

    private boolean fillWavBuffer(int bufferId, int format) {
        if (wavStream == null) return false;
        byte[] data = new byte[BUFFER_SAMPLES * channels * 2];
        try {
            int read = wavStream.read(data);
            if (read <= 0) return false;

            ByteBuffer buf = MemoryUtil.memAlloc(read);
            try {
                buf.put(data, 0, read).flip();
                AL10.alBufferData(bufferId, format, buf, sampleRate);
                samplesDecoded += read / (channels * 2L);
                return true;
            } finally {
                MemoryUtil.memFree(buf);
            }
        } catch (IOException e) {
            LOGGER.error("WAV buffer fill error", e);
            return false;
        }
    }

    private void refillProcessedBuffers() {
        int processed = AL10.alGetSourcei(alSource, AL10.AL_BUFFERS_PROCESSED);
        int format = channels == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;

        while (processed-- > 0) {
            int buf = AL10.alSourceUnqueueBuffers(alSource);
            if (fillBuffer(buf, format)) {
                AL10.alSourceQueueBuffers(alSource, buf);
            }
        }
    }

    private void updatePositionCache() {
        int queued = AL10.alGetSourcei(alSource, AL10.AL_BUFFERS_QUEUED);
        int offset = AL10.alGetSourcei(alSource, AL11.AL_SAMPLE_OFFSET);
        long played = samplesDecoded - (long) queued * BUFFER_SAMPLES + offset;

        cachedPositionFraction = totalSamples > 0
                ? (float) Math.clamp((double) played / totalSamples, 0.0, 1.0)
                : 0f;
        positionCachedAtNanos = System.nanoTime();
    }

    private boolean isTrackFinished() {
        return AL10.alGetSourcei(alSource, AL10.AL_SOURCE_STATE) == AL10.AL_STOPPED &&
                AL10.alGetSourcei(alSource, AL10.AL_BUFFERS_QUEUED) == 0;
    }

    private void onTrackFinished() {
        cleanup();
        resetState();
        advancePlaylist(false);
    }

    private void advancePlaylist(boolean manualNext) {
        if (playlist.isEmpty()) return;

        if (repeatMode == 2) { // repeat one
            play(playlist.get(playlistIndex));
            return;
        }

        if (shuffle) {
            playlistIndex = ThreadLocalRandom.current().nextInt(playlist.size());
        } else if (playlistIndex + 1 < playlist.size()) {
            playlistIndex++;
        } else if (repeatMode == 1) {
            playlistIndex = 0;
        } else {
            return;
        }

        File next = playlist.get(playlistIndex);
        notifyToast(next);
        play(next);
    }

    private void playPreviousLogic() {
        if (playlist.isEmpty()) return;
        if (getPositionSeconds() > 3f) {
            seek(0f);
            return;
        }
        playlistIndex = Math.max(0, playlistIndex - 1);
        File prev = playlist.get(playlistIndex);
        notifyToast(prev);
        play(prev);
    }

    private void notifyToast(File file) {
        // Placeholder — можно улучшить позже
    }

    private void unqueueAllBuffers() {
        if (alSource == -1) return;
        int queued = AL10.alGetSourcei(alSource, AL10.AL_BUFFERS_QUEUED);
        if (queued > 0) {
            int[] tmp = new int[queued];
            AL10.alSourceUnqueueBuffers(alSource, tmp);
        }
    }

    private void refillBuffers() {
        int format = channels == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
        for (int buf : alBuffers) {
            if (fillBuffer(buf, format)) {
                AL10.alSourceQueueBuffers(alSource, buf);
            }
        }
    }

    private void cleanup() {
        if (alSource != -1) {
            AL10.alSourceStop(alSource);
            unqueueAllBuffers();
            AL10.alDeleteBuffers(alBuffers);
            AL10.alDeleteSources(alSource);
            alSource = -1;
        }

        if (oggDecodeBuffer != null) {
            MemoryUtil.memFree(oggDecodeBuffer);
            oggDecodeBuffer = null;
        }
        if (vorbisDecoder != 0) {
            STBVorbis.stb_vorbis_close(vorbisDecoder);
            vorbisDecoder = 0;
        }
        if (fileDataBuffer != null) {
            MemoryUtil.memFree(fileDataBuffer);
            fileDataBuffer = null;
        }
        if (wavStream != null) {
            try { wavStream.close(); } catch (Exception ignored) {}
            wavStream = null;
        }
    }

    private void resetState() {
        playing = false;
        paused = false;
        currentFile = null;
        currentUrlTitle = currentUrlArtist = currentUrlThumbnail = currentUrlId = "";
        samplesDecoded = totalSamples = 0;
        cachedPositionFraction = 0f;
        loading = false;
        urlStatus = "";
    }

    private boolean isValidGeneration(int gen) {
        return gen == urlGeneration;
    }

}