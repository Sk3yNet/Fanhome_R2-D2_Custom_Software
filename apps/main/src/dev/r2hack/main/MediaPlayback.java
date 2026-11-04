package dev.r2hack.main;

import android.media.AudioManager;
import android.media.MediaPlayer;
import android.util.Log;
import java.net.InetAddress;
import java.net.URI;

/**
 * Streamed audio for the DLNA renderer (music, announcements from Home Assistant).
 * Android decodes media in its system media server, which has known flaws on 4.4, so the real
 * protection is what we agree to play: http(s) only, from a private address, and only from the host
 * that sent the play request (Home Assistant serves its own media and text-to-speech).
 */
final class MediaPlayback {
    interface Listener {
        void onStateChanged();
    }

    static final String STOPPED = "STOPPED", PLAYING = "PLAYING", PAUSED = "PAUSED_PLAYBACK",
            TRANSITIONING = "TRANSITIONING", NO_MEDIA = "NO_MEDIA_PRESENT";
    private static final String TAG = "R2Media";

    private final Listener listener;
    private MediaPlayer player;
    private String uri = "", metadata = "";
    private String state = NO_MEDIA;
    private boolean prepared, playWhenReady;

    MediaPlayback(Listener listener) {
        this.listener = listener;
    }

    synchronized String state() {
        return state;
    }

    synchronized String uri() {
        return uri;
    }

    synchronized String metadata() {
        return metadata;
    }

    synchronized int positionMs() {
        try {
            return prepared ? player.getCurrentPosition() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    synchronized int durationMs() {
        try {
            return prepared ? Math.max(0, player.getDuration()) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Checks a stream URL against the rules above. Returns null if acceptable, otherwise the reason.
     * requester is the address the control request came from.
     */
    static String refuse(String url, InetAddress requester) {
        try {
            URI u = new URI(url);
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
            if (!scheme.equals("http") && !scheme.equals("https")) return "only http(s) streams";
            InetAddress host = InetAddress.getByName(u.getHost());
            if (!(host.isSiteLocalAddress() || host.isLinkLocalAddress())) return "stream host is not on the local network";
            if (requester != null && !host.equals(requester) && !requester.isLoopbackAddress()) {
                return "stream must come from the device that sent it (" + requester.getHostAddress() + ")";
            }
            return null;
        } catch (Exception e) {
            return "bad stream URL";
        }
    }

    /** Sets the next stream (DLNA SetAVTransportURI). Playback starts with play(). */
    synchronized void setUri(String url, String meta) {
        release();
        uri = url;
        metadata = meta == null ? "" : meta;
        state = STOPPED;
        changed();
    }

    synchronized void play() {
        if (uri.isEmpty()) return;
        if (player != null && prepared) {
            player.start();
            state = PLAYING;
            changed();
            return;
        }
        if (player != null) {        // still preparing
            playWhenReady = true;
            return;
        }
        try {
            final MediaPlayer mp = new MediaPlayer();
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mp.setDataSource(uri);
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer m) {
                    synchronized (MediaPlayback.this) {
                        if (m != player) return;
                        prepared = true;
                        if (playWhenReady) {
                            m.start();
                            state = PLAYING;
                        } else {
                            state = STOPPED;
                        }
                        changed();
                    }
                }
            });
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer m) {
                    synchronized (MediaPlayback.this) {
                        if (m != player) return;
                        release();
                        state = STOPPED;
                        changed();
                    }
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer m, int what, int extra) {
                    Log.w(TAG, "playback error " + what + "/" + extra);
                    synchronized (MediaPlayback.this) {
                        if (m == player) {
                            release();
                            state = STOPPED;
                            changed();
                        }
                    }
                    return true;
                }
            });
            player = mp;
            prepared = false;
            playWhenReady = true;
            state = TRANSITIONING;
            changed();
            mp.prepareAsync();
        } catch (Exception e) {
            Log.w(TAG, "cannot play " + uri, e);
            release();
            state = STOPPED;
            changed();
        }
    }

    synchronized void pause() {
        if (player != null && prepared && player.isPlaying()) {
            player.pause();
            state = PAUSED;
            changed();
        }
    }

    synchronized void stop() {
        release();
        if (!uri.isEmpty()) state = STOPPED;
        changed();
    }

    synchronized void seekMs(int ms) {
        if (player != null && prepared) player.seekTo(Math.max(0, ms));
    }

    private void release() {
        if (player != null) {
            try {
                player.reset();
            } catch (Exception ignored) {
            }
            player.release();
        }
        player = null;
        prepared = false;
        playWhenReady = false;
    }

    private void changed() {
        listener.onStateChanged();
    }
}
