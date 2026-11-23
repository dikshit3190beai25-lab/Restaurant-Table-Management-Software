package restaurant;

import static restaurant.Models.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RestaurantService {
    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");
    private static final int[][] FLOOR = {
            {2, 4, 4}, {2, 6, 4}, {2, 6, 8},
            {2, 4, 4}, {2, 6, 4}, {2, 6, 8}
    };
    private final RestaurantStore store;
    private final int taxRate;
    private State state;

    RestaurantService(RestaurantStore store, int taxRate) throws IOException {
        this.store = store;
        this.taxRate = taxRate;
        this.state = store.load();
        if (this.state == null) {
            this.state = new State();
            seedMenu();
            store.save(this.state);
        }
    }

    private void seedMenu() {
        seed("Masala Fries", "Starters", 149);
        seed("Paneer Tikka", "Starters", 249);
        seed("Chilli Paneer", "Starters", 269);
        seed("Dal Makhani", "Mains", 229);
        seed("Kadai Paneer", "Mains", 289);
        seed("Veg Biryani", "Mains", 249);
        seed("Butter Naan", "Breads", 59);
        seed("Jeera Rice", "Breads", 129);
        seed("Masala Chai", "Drinks", 79);
        seed("Sweet Lassi", "Drinks", 109);
        seed("Fresh Lime Soda", "Drinks", 99);
        seed("Gulab Jamun", "Desserts", 119);
    }

    private void seed(String name, String category, int rupees) {
        int id = state.nextItemId++;
        state.menu.put(id, new MenuItem(id, name, category, rupees * 100L, true));
    }

    synchronized Map<String, Object> snapshot() {
        List<Map<String, Object>> tables = new ArrayList<>();
        int id = 0;
        for (int[] row : FLOOR) {
            for (int seats : row) {
                id++;
                Order order = state.orders.get(id);
                Bill bill = state.billedTables.get(id);
                Map<String, Object> table = new LinkedHashMap<>();
                table.put("id", id);
                table.put("seats", seats);
                table.put("order", order == null ? null : billView(estimate(0, id, order)));
                table.put("bill", bill == null ? null : billView(bill));
                tables.add(table);
            }
        }
        var menu = state.menu.values().stream().map(item -> Map.of(
                "id", item.id(), "name", item.name(), "category", item.category(),
                "price", item.price(), "available", item.available())).toList();
        return Map.of("tables", tables, "menu", menu, "taxRate", taxRate, "today", today().toString());
    }

    synchronized void assign(int table, String guest, int party) throws RestaurantException, IOException {
        int seats = seats(table);
        guest = cleanText(guest, "Guest name");
        if (party < 1 || party > seats) {
            throw new RestaurantException(400, "Party size must be between 1 and " + seats + ".");
        }
        if (state.orders.containsKey(table) || state.billedTables.containsKey(table)) {
            throw new RestaurantException(409, "This table is already seated.");
        }
        State next = new State(state);
        next.orders.put(table, new Order(guest, party));
        commit(next);
    }

    synchronized void updateLine(int table, int itemId, int quantity, boolean add)
            throws RestaurantException, IOException {
        Order order = activeOrder(table);
        Map<Integer, OrderLine> lines = new LinkedHashMap<>(order.lines());
        OrderLine existing = lines.get(itemId);
        if (add && existing != null) {
            quantity += existing.quantity();
        }
        if (quantity < 0 || quantity > 99) {
            throw new RestaurantException(400, "Quantity must be between 0 and 99.");
        }
        if (quantity == 0) {
            lines.remove(itemId);
        } else if (existing != null) {
            lines.put(itemId, new OrderLine(itemId, existing.name(), existing.price(), quantity));
        } else {
            MenuItem item = state.menu.get(itemId);
            if (item == null || !item.available()) {
                throw new RestaurantException(409, "This menu item is currently unavailable.");
            }
            lines.put(itemId, new OrderLine(itemId, item.name(), item.price(), quantity));
        }
        State next = new State(state);
        next.orders.put(table, new Order(order.guest(), order.party(), lines));
        commit(next);
    }

    synchronized void release(int table) throws RestaurantException, IOException {
        seats(table);
        Order order = state.orders.get(table);
        if (order == null && !state.billedTables.containsKey(table)) {
            throw new RestaurantException(409, "This table is already available.");
        }
        if (order != null && !order.lines().isEmpty()) {
            throw new RestaurantException(409, "Remove ordered items or bill the table first.");
        }
        State next = new State(state);
        next.orders.remove(table);
        next.billedTables.remove(table);
        commit(next);
    }

    synchronized Map<String, Object> checkout(int table) throws RestaurantException, IOException {
        Order order = activeOrder(table);
        if (order.lines().isEmpty()) {
            throw new RestaurantException(400, "Add at least one item before billing.");
        }
        State next = new State(state);
        Bill bill = estimate(next.nextBillId++, table, order);
        next.bills.add(bill);
        next.orders.remove(table);
        next.billedTables.put(table, bill);
        commit(next);
        return billView(bill);
    }

    synchronized void saveMenu(int id, String name, String category, String price, boolean available)
            throws RestaurantException, IOException {
        name = cleanText(name, "Item name");
        category = cleanText(category, "Category");
        if (price == null || !price.matches("[0-9]{1,6}(\\.[0-9]{1,2})?")) {
            throw new RestaurantException(400, "Enter a price with up to two decimal places.");
        }
        long paise = new BigDecimal(price).movePointRight(2).longValueExact();
        if (paise <= 0) {
            throw new RestaurantException(400, "Price must be greater than zero.");
        }
        if (id != 0 && !state.menu.containsKey(id)) {
            throw new RestaurantException(404, "Menu item not found.");
        }
        State next = new State(state);
        int itemId = id == 0 ? next.nextItemId++ : id;
        next.menu.put(itemId, new MenuItem(itemId, name, category, paise, available));
        commit(next);
    }

    synchronized void removeMenu(int id) throws RestaurantException, IOException {
        if (!state.menu.containsKey(id)) {
            throw new RestaurantException(404, "Menu item not found.");
        }
        State next = new State(state);
        next.menu.remove(id);
        commit(next);
    }

    synchronized Map<String, Object> report(LocalDate date) {
        List<Bill> bills = state.bills.stream()
                .filter(bill -> bill.date().equals(date))
                .sorted(Comparator.comparingInt(Bill::id).reversed())
                .toList();
        return Map.of("date", date.toString(), "count", bills.size(),
                "covers", bills.stream().mapToInt(Bill::party).sum(),
                "subtotal", bills.stream().mapToLong(Bill::subtotal).sum(),
                "tax", bills.stream().mapToLong(Bill::tax).sum(),
                "roundOff", bills.stream().mapToLong(Bill::roundOff).sum(),
                "total", bills.stream().mapToLong(Priced::total).sum(),
                "bills", bills.stream().map(RestaurantService::billView).toList());
    }

    private void commit(State next) throws IOException {
        store.save(next);
        state = next;
    }

    private static int seats(int table) throws RestaurantException {
        if (table < 1 || table > FLOOR.length * FLOOR[0].length) {
            throw new RestaurantException(404, "Table not found.");
        }
        int index = table - 1;
        return FLOOR[index / FLOOR[0].length][index % FLOOR[0].length];
    }

    private Order activeOrder(int table) throws RestaurantException {
        seats(table);
        if (state.billedTables.containsKey(table)) {
            throw new RestaurantException(409, "This table has already been billed.");
        }
        Order order = state.orders.get(table);
        if (order == null) {
            throw new RestaurantException(409, "Seat guests at this table first.");
        }
        return order;
    }

    private static String cleanText(String value, String label) throws RestaurantException {
        if (value == null || value.isBlank() || value.length() > 60 ||
                value.chars().anyMatch(Character::isISOControl)) {
            throw new RestaurantException(400, label + " must contain 1–60 printable characters.");
        }
        return value.strip();
    }

    private static LocalDate today() {
        return LocalDate.now(BUSINESS_ZONE);
    }

    private Bill estimate(int id, int table, Order order) {
        return new Bill(id, today(), table, order.guest(), order.party(), taxRate,
                List.copyOf(order.lines().values()), true);
    }

    private static Map<String, Object> billView(Bill bill) {
        return Map.ofEntries(Map.entry("id", bill.id()), Map.entry("date", bill.date().toString()),
                Map.entry("table", bill.table()), Map.entry("guest", bill.guest()), Map.entry("party", bill.party()),
                Map.entry("lines", bill.lines().stream().map(RestaurantService::lineView).toList()),
                Map.entry("subtotal", bill.subtotal()), Map.entry("tax", bill.tax()),
                Map.entry("roundOff", bill.roundOff()), Map.entry("total", bill.total()),
                Map.entry("taxRate", bill.taxRate()));
    }

    private static Map<String, Object> lineView(OrderLine line) {
        return Map.of("itemId", line.itemId(), "name", line.name(), "price", line.price(),
                "quantity", line.quantity(), "total", line.total());
    }
}
