package restaurant;

import static restaurant.Models.*;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

interface RestaurantStore {
    State load() throws IOException;
    void save(State state) throws IOException;
}

final class FileRestaurantStore implements RestaurantStore {
    private final Path file;

    FileRestaurantStore(Path file) {
        this.file = file.toAbsolutePath();
    }

    @Override
    public State load() throws IOException {
        State state = new State();
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(file.toFile(), StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (!"TABLE_AND_TILL\t1".equals(header) && !"TABLE_AND_TILL\t2".equals(header)
                    && !"TABLE_AND_TILL\t3".equals(header)) {
                throw new IOException("Unrecognized restaurant data file.");
            }
            String line;
            while ((line = reader.readLine()) != null) {
                rows.add(line.split("\t", -1));
            }
        } catch (java.io.FileNotFoundException error) {
            if (Files.notExists(file)) {
                return null;
            }
            throw error;
        }

        Map<Integer, Map<Integer, OrderLine>> orderLines = new LinkedHashMap<>();
        Map<Integer, List<OrderLine>> billLines = new LinkedHashMap<>();
        try {
            for (String[] row : rows) {
                if ("order-line".equals(row[0])) {
                    OrderLine line = readLine(row);
                    orderLines.computeIfAbsent(number(row, 1), key -> new LinkedHashMap<>())
                            .put(line.itemId(), line);
                } else if ("bill-line".equals(row[0])) {
                    billLines.computeIfAbsent(number(row, 1), key -> new ArrayList<>()).add(readLine(row));
                }
            }
            for (String[] row : rows) {
                int id = number(row, 1);
                switch (row[0]) {
                    case "ids" -> {
                        state.nextItemId = id;
                        state.nextBillId = number(row, 2);
                    }
                    case "menu" -> state.menu.put(id, new MenuItem(id, row[2], row[3],
                            Long.parseLong(row[4]), Boolean.parseBoolean(row[5])));
                    case "order" -> state.orders.put(id, new Order(row[2], number(row, 3),
                            orderLines.getOrDefault(id, Map.of())));
                    case "bill" -> state.bills.add(new Bill(id, LocalDate.parse(row[2]), number(row, 3),
                            row[4], number(row, 5), number(row, 6), billLines.getOrDefault(id, List.of()),
                            row.length > 7 && Boolean.parseBoolean(row[7]),
                            row.length > 8 ? number(row, 8) : id, row.length > 9 ? Long.parseLong(row[9]) : 0));
                    case "order-line", "bill-line", "billed-table" -> { }
                    default -> throw new IOException("Unknown record in restaurant data file.");
                }
            }
            Map<Integer, Bill> billsById = new LinkedHashMap<>();
            state.bills.forEach(bill -> billsById.put(bill.id(), bill));
            for (String[] row : rows) {
                if ("billed-table".equals(row[0])) {
                    int table = number(row, 1);
                    Bill bill = billsById.get(number(row, 2));
                    Order open = state.orders.get(table);
                    if (bill == null || bill.table() != table || (open != null &&
                            (!open.guest().equals(bill.guest()) || open.party() != bill.party()))) {
                        throw new IOException("Invalid billed table in restaurant data file.");
                    }
                    state.billedTables.put(table, bill);
                }
            }
        } catch (RuntimeException error) {
            throw new IOException("Invalid record in restaurant data file.", error);
        }
        return state;
    }

    private static int number(String[] row, int index) {
        return Integer.parseInt(row[index]);
    }

    private static OrderLine readLine(String[] row) {
        return new OrderLine(number(row, 2), row[3], Long.parseLong(row[4]), number(row, 5));
    }

    @Override
    public void save(State state) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "restaurant-", ".tmp");
        try {
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(temporary.toFile(), StandardCharsets.UTF_8))) {
                write(writer, "TABLE_AND_TILL", 3);
                write(writer, "ids", state.nextItemId, state.nextBillId);
                for (MenuItem item : state.menu.values()) {
                    write(writer, "menu", item.id(), item.name(), item.category(), item.price(), item.available());
                }
                for (Map.Entry<Integer, Order> entry : state.orders.entrySet()) {
                    int table = entry.getKey();
                    Order order = entry.getValue();
                    write(writer, "order", table, order.guest(), order.party());
                    for (OrderLine line : order.lines().values()) {
                        write(writer, "order-line", table, line.itemId(), line.name(), line.price(), line.quantity());
                    }
                }
                for (Bill bill : state.bills) {
                    write(writer, "bill", bill.id(), bill.date(), bill.table(), bill.guest(), bill.party(),
                            bill.taxRate(), bill.roundToRupee(), bill.visitId(), bill.previouslyBilled());
                    for (OrderLine line : bill.lines()) {
                        write(writer, "bill-line", bill.id(), line.itemId(), line.name(), line.price(), line.quantity());
                    }
                }
                for (Map.Entry<Integer, Bill> entry : state.billedTables.entrySet()) {
                    write(writer, "billed-table", entry.getKey(), entry.getValue().id());
                }
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void write(BufferedWriter writer, Object... fields) throws IOException {
        StringBuilder row = new StringBuilder();
        for (Object field : fields) {
            if (!row.isEmpty()) {
                row.append('\t');
            }
            row.append(field);
        }
        writer.write(row.toString());
        writer.newLine();
    }
}
