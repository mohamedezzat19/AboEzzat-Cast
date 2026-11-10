package com.aboezzat.share;

import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.util.HashMap;
import java.util.Map;

/** Inspects the actual tracks in a remote MP4 instead of guessing from its .mp4 suffix. */
final class Mp4TrackInspector {
    private Mp4TrackInspector() {}

    /**
     * @return 1 when the file contains video (including progressive A/V), 2 when it is
     * audio-only, or 0 when the track layout could not be determined.
     */
    static int inspect(String url) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 Chrome/122.0.0.0 Mobile Safari/537.36");
            extractor.setDataSource(url, headers);
            boolean hasVideo = false;
            boolean hasAudio = false;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.containsKey(MediaFormat.KEY_MIME)
                        ? format.getString(MediaFormat.KEY_MIME) : null;
                if (mime == null) continue;
                if (mime.startsWith("video/")) hasVideo = true;
                else if (mime.startsWith("audio/")) hasAudio = true;
            }
            if (hasVideo) return 1;
            if (hasAudio) return 2;
            return 0;
        } catch (Exception ignored) {
            return 0;
        } finally {
            try { extractor.release(); } catch (Exception ignored) {}
        }
    }
}
