package restaurant;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Models {
    private Models() {}

    record MenuItem(int id, String name, String category, long price, boolean available) {}

    interface Priced {
        long total();
    }

    record OrderLine(int itemId, String name, long price, int quantity) implements Priced {
        @Override
        public long total() {
            return price * quantity;
        }
    }

    record Order(String guest, int party, Map<Integer, OrderLine> lines) {
        Order {
            lines = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(lines));
        }

        Order(String guest, int party) {
            this(guest, party, Map.of());
        }
    }

    record Bill(int id, LocalDate date, int table, String guest, int party,
                int taxRate, List<OrderLine> lines, boolean roundToRupee) implements Priced {
        Bill {
            lines = List.copyOf(lines);
        }

        long subtotal() {
            return lines.stream().mapToLong(Priced::total).sum();
        }

        long tax() {
            return (subtotal() * taxRate + 50) / 100;
        }

        private long amountBeforeRoundOff() {
            return subtotal() + tax();
        }

        long roundOff() {
            long amount = amountBeforeRoundOff();
            return roundedAmount(amount) - amount;
        }

        private long roundedAmount(long amount) {
            return roundToRupee ? ((amount + 50) / 100) * 100 : amount;
        }

        @Override
        public long total() {
            return roundedAmount(amountBeforeRoundOff());
        }
    }

    static final class State {
        int nextItemId = 1;
        int nextBillId = 1;
        final Map<Integer, MenuItem> menu = new LinkedHashMap<>();
        final Map<Integer, Order> orders = new LinkedHashMap<>();
        final Map<Integer, Bill> billedTables = new LinkedHashMap<>();
        final List<Bill> bills = new ArrayList<>();

        State() {}

        State(State previous) {
            this.nextItemId = previous.nextItemId;
            this.nextBillId = previous.nextBillId;
            this.menu.putAll(previous.menu);
            this.orders.putAll(previous.orders);
            this.billedTables.putAll(previous.billedTables);
            this.bills.addAll(previous.bills);
        }
    }
}
