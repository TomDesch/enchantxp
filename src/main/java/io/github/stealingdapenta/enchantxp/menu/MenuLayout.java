package io.github.stealingdapenta.enchantxp.menu;

/**
 * One safe layout for every page; only the number of interior rows varies.
 */
final class MenuLayout {

    final int size;
    final int back;
    final int previous;
    final int cancel;
    final int info;
    final int confirm;
    final int sort;
    final int next;
    final int review;
    final int input;
    final int instructions;
    private final int[] content;

    MenuLayout(int rows) {
        if (rows < 3 || rows > 6) {
            throw new IllegalArgumentException("ui.rows must be between 3 and 6");
        }
        size = rows * 9;
        back = size - 9;
        previous = back + 2;
        cancel = back + 4;
        info = back + 5;
        confirm = back + 6;
        sort = back + 7;
        next = back + 8;
        review = ((rows - 1) / 2) * 9 + 4;
        input = review - 1;
        instructions = review + 1;
        content = new int[(rows - 2) * 7];
        for (int index = 0; index < content.length; index++) {
            content[index] = (index / 7 + 1) * 9 + index % 7 + 1;
        }
    }

    int capacity() {
        return content.length;
    }

    int contentSlot(int index) {
        return content[index];
    }

    boolean border(int slot) {
        return slot < 9 || slot >= back || slot % 9 == 0 || slot % 9 == 8;
    }
}
