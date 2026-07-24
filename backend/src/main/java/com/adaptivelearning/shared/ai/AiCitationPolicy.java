package com.adaptivelearning.shared.ai;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AiCitationPolicy {
    private static final Pattern CITATION = Pattern.compile("\\[(S\\d+)]");

    private AiCitationPolicy() {}

    public static Set<String> validCitations(String answer, Set<String> allowed) {
        LinkedHashSet<String> found = new LinkedHashSet<>();
        Matcher matcher = CITATION.matcher(answer == null ? "" : answer);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        if (found.isEmpty() || !allowed.containsAll(found)) {
            return Set.of();
        }
        return Set.copyOf(found);
    }
}
