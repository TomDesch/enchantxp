"""Run after Maven verify; never modifies the built upload JAR."""
from io import BytesIO
import unittest
import zipfile
import xml.etree.ElementTree as ET
from verify_artifact import ROOT, NS, inspect_jar


class ArtifactCheckTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.version = ET.parse(ROOT / "pom.xml").getroot().findtext("m:version", namespaces=NS)
        artifact = ROOT / "target" / ("enchantxp-" + cls.version + ".jar")
        with zipfile.ZipFile(artifact) as jar:
            cls.entries = {name: jar.read(name) for name in jar.namelist()}

    def archive(self, entries):
        stream = BytesIO()
        with zipfile.ZipFile(stream, "w") as jar:
            for name, data in entries.items():
                jar.writestr(name, data)
        stream.seek(0)
        return stream

    def test_valid_artifact_passes(self):
        self.assertGreater(inspect_jar(self.archive(self.entries), self.version), 0)

    def test_metadata_resource_bytecode_and_dependency_defects_are_rejected(self):
        main = "io/github/stealingdapenta/enchantxp/EnchantXpPlugin.class"
        for defect in ("api-version", "version", "resource", "bytecode", "server", "test", "paper-reference", "paper-metadata"):
            entries = dict(self.entries)
            if defect == "api-version":
                entries["plugin.yml"] += b"\napi-version: 1.13\n"
            elif defect == "version":
                entries["plugin.yml"] = entries["plugin.yml"].replace(self.version.encode(), b"0.0.0-invalid")
            elif defect == "resource":
                del entries["config.yml"]
            elif defect == "bytecode":
                entries[main] = entries[main][:6] + bytes([0, 65]) + entries[main][8:]
            elif defect == "server":
                entries["org/bukkit/Server.class"] = entries[main]
            elif defect == "test":
                entries["org/junit/Test.class"] = entries[main]
            elif defect == "paper-reference":
                entries[main] += b"com/destroystokyo/paper/Example"
            elif defect == "paper-metadata":
                entries["paper-plugin.yml"] = b"name: EnchantXP"
            with self.subTest(defect=defect), self.assertRaises(ValueError):
                inspect_jar(self.archive(entries), self.version)


if __name__ == "__main__":
    unittest.main()
