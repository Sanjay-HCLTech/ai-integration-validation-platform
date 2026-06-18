package com.hcl.observability.validation;

import com.hcl.observability.trace.TimelineEvent;
import com.hcl.observability.trace.TimelineService;
import com.hcl.observability.trace.TracePhase;
import com.hcl.observability.trace.TraceStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

@Service
public class BusinessValidationService {

    private final TimelineService timelineService;

    public BusinessValidationService(TimelineService timelineService) {
        this.timelineService = timelineService;
    }

    public BusinessValidationResult validateScope(String category, String scope) throws Exception {
        return validate(category, timelineService.buildTimeline(scope));
    }

    public BusinessValidationResult validate(String category, List<TimelineEvent> timeline) {
        String normalizedCategory = normalizeCategory(category);
        List<TimelineEvent> safeTimeline = timeline == null ? Collections.emptyList() : timeline;

        if (safeTimeline.isEmpty()) {
            return failed(normalizedCategory,
                    expectedFlow(normalizedCategory),
                    "NO_EVENTS",
                    "OBSERVABILITY",
                    "No timeline events were available for business validation");
        }

        if (containsFailure(safeTimeline)) {
            TimelineEvent failure = firstFailure(safeTimeline);
            return failed(normalizedCategory,
                    expectedFlow(normalizedCategory),
                    actualFlow(safeTimeline),
                    system(failure),
                    "Error event detected at " + system(failure));
        }

        switch (normalizedCategory) {
            case "DATAHUB":
                return validateOrderedPhases(normalizedCategory, safeTimeline,
                        phases(TracePhase.PUBLISH, TracePhase.CONSUME, TracePhase.CONFIRM),
                        "PUBLISH -> CONSUME -> CONFIRM");
            case "VRP":
                return validateOrderedPhases(normalizedCategory, safeTimeline,
                        phases(TracePhase.REQUEST, TracePhase.REPLY),
                        "REQUEST -> RESPONSE");
            case "NORDICS":
                return validateOrderedPhases(normalizedCategory, safeTimeline,
                        phases(TracePhase.PUBLISH, TracePhase.CONSUME, TracePhase.PROCESS),
                        "PUBLISH -> SUBSCRIBER -> DOWNSTREAM");
            case "APIGEE":
                return validateOrderedPhases(normalizedCategory, safeTimeline,
                        phases(TracePhase.REQUEST, TracePhase.REPLY),
                        "API REQUEST -> HTTP 200");
            default:
                return validateOrderedPhases(normalizedCategory, safeTimeline,
                        phases(TracePhase.REQUEST, TracePhase.REPLY),
                        "REQUEST -> REPLY");
        }
    }

    private BusinessValidationResult validateOrderedPhases(
            String category,
            List<TimelineEvent> timeline,
            List<TracePhase> expectedPhases,
            String expectedText) {
        int cursor = 0;
        List<TracePhase> actualPhases = new ArrayList<>();
        String failurePoint = "NA";
        TracePhase missing = null;

        for (TracePhase expectedPhase : expectedPhases) {
            TimelineEvent matched = firstPhaseAtOrAfter(timeline, expectedPhase, cursor);
            if (matched == null) {
                missing = expectedPhase;
                failurePoint = likelyFailurePoint(timeline);
                break;
            }
            actualPhases.add(expectedPhase);
            cursor = timeline.indexOf(matched) + 1;
        }

        String actualText = actualText(actualPhases, timeline);
        if (missing == null) {
            return new BusinessValidationResult(
                    category,
                    "SUCCESS",
                    expectedText,
                    actualText,
                    "MATCH",
                    "NA",
                    Collections.emptyList(),
                    Collections.emptyList());
        }

        String gap = "Missing " + missing + " after " + lastActualPhase(actualPhases);
        return failed(category, expectedText, actualText, failurePoint, gap);
    }

    private TimelineEvent firstPhaseAtOrAfter(List<TimelineEvent> timeline, TracePhase phase, int startIndex) {
        for (int index = Math.max(0, startIndex); index < timeline.size(); index++) {
            TimelineEvent event = timeline.get(index);
            if (event != null && event.getPhase() == phase) {
                return event;
            }
        }
        return null;
    }

    private boolean containsFailure(List<TimelineEvent> timeline) {
        return firstFailure(timeline) != null;
    }

    private TimelineEvent firstFailure(List<TimelineEvent> timeline) {
        for (TimelineEvent event : timeline) {
            if (event == null) {
                continue;
            }
            TraceStatus status = event.getStatus();
            if (event.getPhase() == TracePhase.ERROR
                    || status == TraceStatus.ERROR
                    || status == TraceStatus.FAILED) {
                return event;
            }
        }
        return null;
    }

    private BusinessValidationResult failed(
            String category,
            String expected,
            String actual,
            String failurePoint,
            String gap) {
        List<String> gaps = Collections.singletonList(gap);
        List<String> actions = Collections.singletonList("Check " + failurePoint
                + " for " + gap + " using JobID/CorrID in the local evidence files.");
        return new BusinessValidationResult(
                category,
                "FAIL",
                expected,
                actual,
                "MISMATCH",
                failurePoint,
                gaps,
                actions);
    }

    private List<TracePhase> phases(TracePhase... phases) {
        return Arrays.asList(phases);
    }

    private String expectedFlow(String category) {
        switch (category) {
            case "DATAHUB":
                return "PUBLISH -> CONSUME -> CONFIRM";
            case "VRP":
                return "REQUEST -> RESPONSE";
            case "NORDICS":
                return "PUBLISH -> SUBSCRIBER -> DOWNSTREAM";
            case "APIGEE":
                return "API REQUEST -> HTTP 200";
            default:
                return "REQUEST -> REPLY";
        }
    }

    private String actualFlow(List<TimelineEvent> timeline) {
        List<String> phases = new ArrayList<>();
        for (TimelineEvent event : timeline) {
            if (event != null && event.getPhase() != null && !phases.contains(event.getPhase().name())) {
                phases.add(event.getPhase().name());
            }
        }
        return phases.isEmpty() ? "NO_EVENTS" : String.join(" -> ", phases);
    }

    private String actualText(List<TracePhase> matchedPhases, List<TimelineEvent> timeline) {
        if (matchedPhases == null || matchedPhases.isEmpty()) {
            return actualFlow(timeline);
        }

        List<String> values = new ArrayList<>();
        for (TracePhase phase : matchedPhases) {
            values.add(phase.name());
        }
        return String.join(" -> ", values);
    }

    private String lastActualPhase(List<TracePhase> actualPhases) {
        if (actualPhases == null || actualPhases.isEmpty()) {
            return "START";
        }
        return actualPhases.get(actualPhases.size() - 1).name();
    }

    private String likelyFailurePoint(List<TimelineEvent> timeline) {
        if (timeline == null || timeline.isEmpty()) {
            return "OBSERVABILITY";
        }
        TimelineEvent last = timeline.get(timeline.size() - 1);
        return system(last);
    }

    private String system(TimelineEvent event) {
        if (event == null || event.getSystem() == null || event.getSystem().trim().isEmpty()) {
            return "UNKNOWN";
        }
        return event.getSystem();
    }

    private String normalizeCategory(String category) {
        if (category == null || category.trim().isEmpty()) {
            return "GENERIC";
        }

        String normalized = category.trim().toUpperCase(Locale.ROOT);
        if ("JMS".equals(normalized)) {
            return "DATAHUB";
        }
        if ("SOAP".equals(normalized)) {
            return "VRP";
        }
        if ("RABBIT".equals(normalized) || "RABBITMQ".equals(normalized)) {
            return "NORDICS";
        }
        if ("REST".equals(normalized)) {
            return "APIGEE";
        }
        return normalized;
    }
}
