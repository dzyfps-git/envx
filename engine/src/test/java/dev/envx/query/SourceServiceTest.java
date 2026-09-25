package dev.envx.query;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SourceServiceTest {
    @Test
    void remapConflictsNameTheInheritedMinecraftMembers() { // 7 of 594 pack jars failed to remap before 1.3.1
        String log = """
                [WARN] Cannot remap foo because it does not exist in any of the targets [bar]
                [WARN] Mapping target name conflicts detected:
                [WARN]   METHODs earth/terrarium/botarium/common/fluid/impl/SimpleFluidContainer/[clear, net/minecraft/class_3829/method_5448]()V -> clear
                [WARN]   FIELDs a/b/C/[net/minecraft/class_1/field_2, own]I -> value
                [ERROR] There were unfixable conflicts.
                """;
        assertEquals(Set.of("net/minecraft/class_3829/method_5448()V", "net/minecraft/class_1/field_2I"), SourceService.conflicting(log));
    }
}
