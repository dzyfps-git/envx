package dev.sevli.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UpdateReportTest {
    @Test
    void lineDiffListsLinesPresentOnOneSideOnly() {
        List<String> a = List.of("{", "  \"maxMobs\": 40,", "  \"enabled\": true,", "}");
        List<String> b = List.of("{", "  \"maxMobs\": 60,", "  \"enabled\": true,", "  \"radius\": 8", "}");
        assertEquals(List.of("- \"maxMobs\": 40,", "+ \"maxMobs\": 60,", "+ \"radius\": 8"), UpdateReport.lineDiff(a, b, 8));
    }

    @Test
    void lineDiffCapsEachSide() {
        List<String> b = List.of("a", "b", "c");
        assertEquals(List.of("+ a", "+ ... +2 more"), UpdateReport.lineDiff(List.of(), b, 1));
    }
}
