package dev.sevli.query;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Log and crash-report parsing and grouping; no index needed. */
class LogServiceTest {
    private static final LocalDate DAY = LocalDate.of(2026, 9, 23);

    @Test
    void errorsFilterTakesDatesAndText() {
        // Agents ask for a day; it selects by time instead of matching message text.
        LogService.Filter f = LogService.Filter.parse("2026-09-10", DAY);
        assertEquals(new LogService.Filter(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 10), true, null), f);
        assertEquals("on 2026-09-10", f.describe());
        // Month-day: the latest such day up to the newest log.
        assertEquals(LocalDate.of(2026, 9, 10), LogService.Filter.parse("09-10", DAY).from());
        assertEquals(LocalDate.of(2025, 12, 30), LogService.Filter.parse("12-30", DAY).from());
        f = LogService.Filter.parse("09-01..09-10 neruina", DAY);
        assertEquals(LocalDate.of(2026, 9, 1), f.from());
        assertEquals(LocalDate.of(2026, 9, 10), f.to());
        assertEquals("neruina", f.text());
        f = LogService.Filter.parse("09-20..", DAY);
        assertEquals("from 2026-09-20", f.describe());
        // Not dates: plain text as before.
        assertEquals(new LogService.Filter(null, null, false, "1.2.0-rc3"), LogService.Filter.parse("1.2.0-rc3", DAY));
        assertEquals(new LogService.Filter(null, null, false, "13-45"), LogService.Filter.parse("13-45", DAY));
        assertEquals(new LogService.Filter(null, null, false, null), LogService.Filter.parse(" ", DAY));
    }

    @Test
    void recordsKeepTheirTraceAndRootCause() {
        List<LogService.Event> ev = LogService.parseLog(List.of(
                "[12:00:00] [Server thread/INFO]: Done (3.2s)!",
                "[12:49:58] [Netty Server IO #4/ERROR]: Exception occurred in netty pipeline",
                "java.io.IOException: connection aborted",
                "\tat sun.nio.ch.SocketDispatcher.read0(Native Method) ~[?:?]",
                "Caused by: java.lang.IllegalStateException: boom",
                "\tat knot//dev.demo.Foo.bar(Foo.java:12) ~[demo.jar:?]",
                "\t... 12 more",
                "[12:50:00] [Server thread/WARN]: Skipped emitting ENTITY_MOUNT game event"), DAY);
        assertEquals(2, ev.size()); // INFO is not collected
        LogService.Event e = ev.getFirst();
        assertEquals("ERROR", e.level());
        assertEquals("java.io.IOException: connection aborted", e.exception());
        assertEquals("java.lang.IllegalStateException: boom", e.cause());
        assertEquals("dev.demo.Foo", e.frames().getFirst()[0]); // frames of the root cause
        assertEquals(DAY.atTime(12, 49, 58), e.time());
    }

    @Test
    void aStackLoggedLineByLineIsOneRecordThatNamesTheMixinMod() {
        List<LogService.Event> ev = LogService.parseLog(List.of(
                "[01:51:42] [Server thread/WARN]: java.base/java.lang.Thread.getStackTrace(Thread.java:1619)",
                "[01:51:42] [Server thread/WARN]: knot//net.minecraft.class_1657.handler$hjg000$prominent$checkSet(class_1657.java:41386)",
                "[01:51:42] [Server thread/WARN]: knot//net.minecraft.class_1657.method_6073(class_1657.java)",
                "[01:51:43] [Server thread/WARN]: Not enough items to trade with player 4A45"), DAY);
        assertEquals(2, ev.size());
        assertEquals(LogService.STACK_ONLY, ev.getFirst().message());
        assertEquals(3, ev.getFirst().frames().size());
        assertTrue(LogService.signature(ev.getFirst()).endsWith("net.minecraft.class_1657.handler$hjg000$prominent$checkSet"));
    }

    @Test
    void occurrencesThatDifferOnlyInIdsAndNumbersShareASignature() {
        List<LogService.Event> ev = LogService.parseLog(List.of(
                "[10:00:00] [Worker/ERROR]: Parsing error loading custom advancement copycats:recipes/crafting/copycat_ladder: Missing criteria",
                "[10:00:01] [Worker/ERROR]: Parsing error loading custom advancement copycats:recipes/crafting/copycat_bars: Missing criteria",
                "[10:00:02] [Server thread/ERROR]: POI data mismatch: already registered at class_2338{x=-25089, y=-44, z=12526}",
                "[10:00:03] [Server thread/ERROR]: POI data mismatch: already registered at class_2338{x=2048, y=1, z=834}",
                "[10:00:04] [Server thread/ERROR]: Failed to parse LootFunction of type net.minecraft.class_5339@4e5fd7e6",
                "[10:00:05] [Server thread/ERROR]: Failed to parse LootFunction of type net.minecraft.class_5339@16b514b6"), DAY);
        assertEquals(LogService.signature(ev.get(0)), LogService.signature(ev.get(1)));
        assertEquals(LogService.signature(ev.get(2)), LogService.signature(ev.get(3)));
        assertEquals(LogService.signature(ev.get(4)), LogService.signature(ev.get(5)));
        assertNotEquals(LogService.signature(ev.get(0)), LogService.signature(ev.get(2)));
    }

    @Test
    void crashReportsGiveTheirTimeCauseAndModVersionsOfTheTime() {
        List<LogService.Event> ev = LogService.parseCrash(List.of(
                "---- Minecraft Crash Report ----",
                "// Shall we play a game?",
                "",
                "Time: 2026-09-10 09:42:19",
                "Description: Ticking entity",
                "",
                "com.bawnorton.neruina.exception.TickingException: Exception occurred while handling errored entity",
                "\tat knot//com.bawnorton.neruina.handler.TickHandler.handleErroredEntity(TickHandler.java:285)",
                "Caused by: java.lang.NullPointerException: entity is null",
                "\tat knot//dev.example.Trust.isCompanion(Trust.java:71)",
                "",
                "A detailed walkthrough of the error, its code path and all known details is as follows:",
                "-- System Details --",
                "\tFabric Mods: ",
                "\t\tneruina: Neruina 2.3.1-beta.1",
                "\t\texample_compat: Example Compat 1.2.0-rc3",
                "\t\t\tnested_lib: Lib 1.0",
                "\tLoaded Shaderpack: none"), "crash-2026-09-10_09.42.19-server.txt");
        LogService.Event e = ev.getFirst();
        assertEquals("crash: Ticking entity", e.message());
        assertEquals("java.lang.NullPointerException: entity is null", e.cause());
        assertEquals("dev.example.Trust", e.frames().getFirst()[0]);
        assertEquals("1.2.0-rc3", e.versions().get("example_compat"));
        assertEquals(2, e.versions().size()); // nested jars are not top-level mods
    }
}
