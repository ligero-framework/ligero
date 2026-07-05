package com.ligero.http;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Minimal {@code Accept} header negotiation (RFC 9110 §12.5.1): media
 * ranges ordered by q-value, with {@code *}/{@code type/*} wildcards.
 */
final class Accepts {

    private record MediaRange(String type, String subtype, double q) {
    }

    private final List<MediaRange> ranges = new ArrayList<>();

    Accepts(String acceptHeader) {
        if (acceptHeader == null || acceptHeader.isBlank()) {
            ranges.add(new MediaRange("*", "*", 1.0));
            return;
        }
        for (String part : acceptHeader.split(",")) {
            String[] pieces = part.trim().split(";");
            String[] typeParts = pieces[0].trim().split("/", 2);
            if (typeParts.length != 2) {
                continue;
            }
            double q = 1.0;
            for (int i = 1; i < pieces.length; i++) {
                String param = pieces[i].trim();
                if (param.startsWith("q=")) {
                    try {
                        q = Double.parseDouble(param.substring(2));
                    } catch (NumberFormatException ignored) {
                        q = 0.0;
                    }
                }
            }
            ranges.add(new MediaRange(typeParts[0].trim().toLowerCase(),
                typeParts[1].trim().toLowerCase(), q));
        }
        ranges.sort(Comparator.comparingDouble((MediaRange r) -> r.q).reversed());
    }

    boolean accepts(String mimeType) {
        return quality(mimeType) > 0;
    }

    String preferred(List<String> offered) {
        String best = null;
        double bestQ = 0;
        for (String offer : offered) {
            double q = quality(offer);
            if (q > bestQ) {
                bestQ = q;
                best = offer;
            }
        }
        return best;
    }

    private double quality(String mimeType) {
        String[] parts = mimeType.split(";")[0].trim().toLowerCase().split("/", 2);
        if (parts.length != 2) {
            return 0;
        }
        for (MediaRange range : ranges) {
            boolean typeMatches = range.type.equals("*") || range.type.equals(parts[0]);
            boolean subtypeMatches = range.subtype.equals("*") || range.subtype.equals(parts[1]);
            if (typeMatches && subtypeMatches) {
                return range.q;
            }
        }
        return 0;
    }
}
