package io.github.stealingdapenta.enchantxp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.meta.ItemMeta;

class MemoryItemMeta implements ItemMeta {
    private String name;
    private String localized;
    private List<String> lore;
    private boolean unbreakable;
    private final Map<Enchantment, Integer> enchants = new HashMap<Enchantment, Integer>();
    private final Set<ItemFlag> flags = new HashSet<ItemFlag>();

    @Override public boolean hasDisplayName() { return name != null; }
    @Override public String getDisplayName() { return name; }
    @Override public void setDisplayName(String value) { name = value; }
    @Override public boolean hasLocalizedName() { return localized != null; }
    @Override public String getLocalizedName() { return localized; }
    @Override public void setLocalizedName(String value) { localized = value; }
    @Override public boolean hasLore() { return lore != null && !lore.isEmpty(); }
    @Override public List<String> getLore() { return lore == null ? null : new ArrayList<String>(lore); }
    @Override public void setLore(List<String> value) { lore = value == null ? null : new ArrayList<String>(value); }
    @Override public boolean hasEnchants() { return !enchants.isEmpty(); }
    @Override public boolean hasEnchant(Enchantment enchantment) { return enchants.containsKey(enchantment); }
    @Override public int getEnchantLevel(Enchantment enchantment) { return enchants.getOrDefault(enchantment, 0); }
    @Override public Map<Enchantment, Integer> getEnchants() { return Collections.unmodifiableMap(new HashMap<Enchantment, Integer>(enchants)); }
    @Override public boolean addEnchant(Enchantment enchantment, int level, boolean ignore) {
        if (!ignore && (level < enchantment.getStartLevel() || level > enchantment.getMaxLevel())) { return false; }
        Integer previous = enchants.put(enchantment, level);
        return previous == null || previous != level;
    }
    @Override public boolean removeEnchant(Enchantment enchantment) { return enchants.remove(enchantment) != null; }
    @Override public boolean hasConflictingEnchant(Enchantment enchantment) {
        return enchants.keySet().stream().anyMatch(existing -> existing.conflictsWith(enchantment));
    }
    @Override public void addItemFlags(ItemFlag... values) { flags.addAll(Arrays.asList(values)); }
    @Override public void removeItemFlags(ItemFlag... values) { flags.removeAll(Arrays.asList(values)); }
    @Override public Set<ItemFlag> getItemFlags() { return Collections.unmodifiableSet(flags); }
    @Override public boolean hasItemFlag(ItemFlag flag) { return flags.contains(flag); }
    @Override public boolean isUnbreakable() { return unbreakable; }
    @Override public void setUnbreakable(boolean value) { unbreakable = value; }
    @Override public Spigot spigot() { return new Spigot(); }
    @Override public Map<String, Object> serialize() {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put("name", name);
        values.put("localized", localized);
        values.put("lore", lore);
        values.put("enchants", new HashMap<Enchantment, Integer>(enchants));
        values.put("flags", new HashSet<ItemFlag>(flags));
        values.put("unbreakable", unbreakable);
        return values;
    }
    @Override public MemoryItemMeta clone() {
        MemoryItemMeta copy = new MemoryItemMeta();
        copyTo(copy);
        return copy;
    }
    protected void copyTo(MemoryItemMeta copy) {
        copy.name = name;
        copy.localized = localized;
        copy.setLore(lore);
        copy.unbreakable = unbreakable;
        copy.enchants.putAll(enchants);
        copy.flags.addAll(flags);
    }
    @Override public boolean equals(Object other) {
        return other instanceof MemoryItemMeta && serialize().equals(((MemoryItemMeta) other).serialize());
    }
    @Override public int hashCode() { return Objects.hash(serialize()); }
}
