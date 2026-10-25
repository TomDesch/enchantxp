package io.github.stealingdapenta.enchantxp.configuration.values;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static io.github.stealingdapenta.enchantxp.testsupport.YamlDocuments.yaml;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConfigValuesTest {
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
