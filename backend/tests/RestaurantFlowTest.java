package restaurant;

import java.nio.file.Files;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

public final class RestaurantFlowTest {
    public static void main(String[] args) throws Exception {
        var directory = Files.createTempDirectory("table-till-test-");
        var file = directory.resolve("restaurant.tsv");
        try {
            var service = new RestaurantService(new FileRestaurantStore(file), 5);
            check(((List<?>) service.snapshot().get("tables")).size() == 18, "Table count");
            for (int id = 1; id <= 9; id++) {
                check(table(service, id).get("seats").equals(table(service, id + 9).get("seats")),
                        "Added tables mirror the original seat capacities");
            }
            reject(404, () -> service.assign(19, "Demo", 1));
            reject(400, () -> service.assign(18, "Demo", 9));
            service.assign(18, "Demo", 8);
            check(((Map<?, ?>) table(service, 18).get("order")).get("party").equals(8),
                    "Table 18 seats a party of eight");
            service.release(18);
            reject(400, () -> service.assign(1, "Demo", 3));
            var pool = Executors.newFixedThreadPool(2);
            try {
                Callable<Boolean> seat = () -> {
                    try {
                        service.assign(1, "Demo \"Guest\"", 2);
                        return true;
                    } catch (RestaurantException error) {
                        check(error.status() == 409, "Duplicate seating rejection");
                        return false;
                    }
                };
                var seats = pool.invokeAll(List.of(seat, seat));
                check(seats.get(0).get() != seats.get(1).get(), "Concurrent seating");
            } finally {
                pool.shutdownNow();
            }

            service.saveMenu(0, "Special", "Extras", "100.10", true);
            service.saveMenu(13, "Special", "Extras", "100.10", false);
            reject(409, () -> service.updateLine(1, 13, 1, true));
            service.saveMenu(13, "Special", "Extras", "100.10", true);
            service.updateLine(1, 13, 1, true);
            var preview = (Map<?, ?>) table(service, 1).get("order");
            check(preview.get("total").equals(10500L), "Preview shows the running bill");
            check(table(service, 1).get("bill") == null, "Preview leaves the order open");
            service.updateLine(1, 13, 1, true);
            service.saveMenu(13, "Renamed", "Extras", "199.90", true);
            service.updateLine(1, 2, 1, true);
            service.updateLine(1, 2, 0, false);
            service.updateLine(1, 13, 3, false);
            service.removeMenu(13);
            reject(409, () -> service.release(1));

            var restarted = new RestaurantService(new FileRestaurantStore(file), 5);
            Map<String, Object> bill = restarted.checkout(1);
            check(bill.get("subtotal").equals(30030L), "Order quantities and saved unit price");
            check(bill.get("tax").equals(1502L), "Tax rounded to nearest paisa");
            check(bill.get("roundOff").equals(-32L), "Round-off down");
            check(bill.get("total").equals(31500L), "Rounded total");
            var lines = (List<?>) bill.get("lines");
            check(lines.size() == 1 && ((Map<?, ?>) lines.get(0)).get("name").equals("Special"),
                    "Only retained item appears using its original name");
            reject(409, () -> restarted.checkout(1));
            reject(409, () -> restarted.updateLine(1, 1, 1, true));
            reject(409, () -> restarted.assign(1, "Next guest", 1));

            var finalState = new RestaurantService(new FileRestaurantStore(file), 9);
            check(bill.equals(table(finalState, 1).get("bill")), "Billed table persists after restart");
            finalState.release(1);
            check(table(finalState, 1).get("bill") == null, "Released table is available");
            LocalDate date = LocalDate.parse((String) bill.get("date"));
            var report = finalState.report(date);
            check(report.get("count").equals(1), "Report count");
            check(report.get("covers").equals(2), "Report covers");
            check(report.get("total").equals(31500L), "Stored bill retains historical tax and round-off");
            check(report.get("roundOff").equals(-32L), "Report includes the round-off adjustment");
            check(finalState.report(date.minusDays(1)).get("count").equals(0), "Past date filtering");
            finalState.assign(1, "Available after restart", 1);
            reject(400, () -> finalState.checkout(1));
            finalState.release(1);
            verifyRoundUpAndLegacyBills(file);
            System.out.println("PASS: seating, concurrent assignment, menu, orders, checkout, reports and persistence.");
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }
    }

    private static Map<?, ?> table(RestaurantService service, int id) {
        return (Map<?, ?>) ((List<?>) service.snapshot().get("tables")).get(id - 1);
    }

    private static void verifyRoundUpAndLegacyBills(java.nio.file.Path file) throws Exception {
        Files.writeString(file, "TABLE_AND_TILL\t1\n"
                + "ids\t2\t2\nmenu\t1\tSample\tMains\t44171\ttrue\n"
                + "bill\t1\t2026-11-21\t1\tPrevious guest\t1\t5\n"
                + "bill-line\t1\t1\tSample\t44171\t1\n");
        var service = new RestaurantService(new FileRestaurantStore(file), 5);
        service.assign(1, "Current guest", 1);
        service.updateLine(1, 1, 1, true);
        var bill = service.checkout(1);
        check(bill.get("tax").equals(2209L), "Sample tax");
        check(bill.get("roundOff").equals(20L), "₹463.80 has a ₹0.20 adjustment");
        check(bill.get("total").equals(46400L), "₹463.80 rounds to ₹464");

        service.saveMenu(0, "Rounding midpoint", "Extras", "10.00", true);
        service.assign(2, "Another guest", 1);
        service.updateLine(2, 2, 1, true);
        check(service.checkout(2).get("total").equals(1100L), "₹10.50 rounds up");

        var restarted = new RestaurantService(new FileRestaurantStore(file), 5);
        var oldReport = restarted.report(LocalDate.of(2026, 11, 21));
        var oldBill = ((List<?>) oldReport.get("bills")).stream().map(entry -> (Map<?, ?>) entry)
                .filter(entry -> entry.get("id").equals(1)).findFirst().orElseThrow();
        check(oldBill.get("total").equals(46380L), "Previous bills keep their original amount");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void reject(int status, Action action) throws Exception {
        try {
            action.run();
            throw new AssertionError("Expected error " + status);
        } catch (RestaurantException error) {
            check(error.status() == status, "Expected error " + status + " but got " + error.status());
        }
    }

    private interface Action {
        void run() throws Exception;
    }
}
