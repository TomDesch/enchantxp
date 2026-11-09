package io.github.stealingdapenta.enchantxp.configuration.values;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static io.github.stealingdapenta.enchantxp.testsupport.YamlDocuments.yaml;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConfigValuesTest {
    @Test
    void unknownKeysReportRootAndNestedPathsWithoutLeadingDot() throws Exception {
        YamlConfiguration config = yaml("permission:\n  typo: true\n");
        IllegalArgumentException nested = assertThrows(IllegalArgumentException.class,
                () -> ConfigValues.keys(config.getConfigurationSection("permission"), "enabled"));
        assertEquals("permission.typo is unknown", nested.getMessage());
        IllegalArgumentException root = assertThrows(IllegalArgumentException.class,
                () -> ConfigValues.keys(config, "config-version"));
        assertEquals("permission is unknown", root.getMessage());
    }

    @Test
    void unknownKeyInOrphanedSectionReportsPlainKey() {
        ConfigurationSection section = mock(ConfigurationSection.class);
        when(section.getCurrentPath()).thenReturn(null);
        when(section.getKeys(false)).thenReturn(Collections.singleton("typo"));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ConfigValues.keys(section, "enabled"));
        assertEquals("typo is unknown", failure.getMessage());
    }

    @Test
    void missingPackagedDefaultsReportTheResourcePath() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ConfigValues.readDefaults(null));
        assertTrue(failure.getMessage().contains("/config.yml"));
    }

    @Test
    void packagedDefaultsCloseTheirStreamOnSuccessAndMalformedYaml() throws Exception {
        ByteArrayInputStream valid = spy(new ByteArrayInputStream("config-version: 1\n".getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, ConfigValues.readDefaults(valid).getInt("config-version"));
        verify(valid).close();
        ByteArrayInputStream malformed = spy(new ByteArrayInputStream("config-version: [\n".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalStateException.class, () -> ConfigValues.readDefaults(malformed));
        verify(malformed).close();
    }

    @Test
    void requiredChildrenReportTheirPathInsteadOfReturningNullForAbsentOrMalformedSections() throws Exception {
        for (String document : new String[]{"", "enchantments:\n  DAMAGE_ALL: false\n",
                "enchantments:\n  DAMAGE_ALL: []\n"}) {
            YamlConfiguration config = yaml(document);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> ConfigValues.requiredSection(config, "enchantments.DAMAGE_ALL"));
            assertEquals("enchantments.DAMAGE_ALL must be a section", failure.getMessage());
        }
    }
}
