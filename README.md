# Table & Till

Table & Till is a restaurant billing and table management project for **25CSE0202 Programming in Java**. The React interface handles the cashier’s workflow, and the Java backend validates operations, calculates bills, and saves data.

## Run the project

**Requirements** — JDK 17 or later. Install Node.js 20 or later with npm if you need to rebuild the frontend.

The downloadable project includes the compiled React frontend. From the project folder on macOS or Linux, run:

```bash
./start.sh
```

Open [Table & Till](http://127.0.0.1:8080). Leave the terminal running while using the app. A fresh setup has 18 empty tables and a sample menu. Use a single Java process for a given data file.

To build after changing the React source:

```bash
cd frontend
npm install
npm run build
cd ..
./start.sh
```

For a React development server, keep Java running on port 8080 and run `npm run dev` inside `frontend` in a second terminal. Vite prints the development URL.

Windows PowerShell, from the project root:

```powershell
New-Item -ItemType Directory -Force backend/out
javac --release 17 -d backend/out (Get-ChildItem backend/src/restaurant/*.java).FullName
java -cp backend/out restaurant.Main
```

### Configuration and data

| Setting | Default | Purpose |
| --- | --- | --- |
| `PORT` | `8080` | Port for the local Java server |
| `DATA_FILE` | `backend/data/restaurant.tsv` | Saved menu, active orders, billed tables awaiting release, and bill history |
| `TAX_RATE` | `5` | Configurable whole-number percentage for the classroom demo |

The sample tax rate is a project setting. Daily reports use the India business date (`Asia/Kolkata`). Changes to the tax setting apply to future bills. Saved bills retain their original rate, prices, and payable amount.

## Demo workflow

1. Open **Menu**. Add a dish, edit its price and category, or toggle availability. Delete a sample item if needed.
2. On **Floor plan**, combine **Available** and the **Seats** filter to find a table of the exact size you want. Enter a guest name and watch the seating diagram update as you change the party size. Select **Assign table** to show the filled seats on the floor plan.
3. Select **Add item** whenever guests order another round. Use **−**, **＋**, or **×** to update the same order.
4. Select **Preview bill** to see the running receipt. Use **Back to order** to keep adding items, or **Finalize bill** when the guests are ready to settle. The receipt shows itemized prices, tax, round-off, and the amount payable. Use **Print bill** for a paper or PDF copy.
5. The table shows **Billed** after finalization. Choose **Add another order** for the same party to start a fresh round. Each round has its own bill ID. The table lists all bills for this visit and sums their rounded amounts. Daily sales count each bill once and guests on the first bill of the visit. Select **Release table** after the guests leave. To reopen the receipt, select the bill in **Daily sales**.
6. Restart the backend and reopen the report to demonstrate persistence.

To demonstrate billing with the original seed menu, order two Butter Naans and add a Sweet Lassi in the next round. The subtotal is ₹227.00, the demo tax is ₹11.35, and the round-off is −₹0.35. The amount payable is ₹238.00. An amount of ₹463.80 rounds up to ₹464.00.

## Java syllabus mapping

| Topic | Where it appears |
| --- | --- |
| Classes, objects, methods, constructors, `this`, `static` | `RestaurantService`, `FileRestaurantStore`, and the domain models |
| Inheritance and interfaces | `RestaurantException extends Exception`. `FileRestaurantStore implements RestaurantStore`. |
| Abstraction and polymorphism | The service uses a `RestaurantStore` interface. Both `OrderLine` and `Bill` implement `Priced.total()` and calculate their own totals. |
| Multidimensional arrays | `RestaurantService.FLOOR` is a 2D seat-capacity array. A table ID maps to one row and column. |
| Strings, formatting, `StringBuilder`, wrapper classes | `cleanText` validates and trims input. `split` reads text records. `StringBuilder` assembles storage rows and JSON. `Integer` is the generic map-key type. |
| Checked and unchecked exceptions | `RestaurantException` and `IOException` are checked. Invalid number/date formats can throw unchecked exceptions. `Main` translates failures into HTTP responses using `try`, `catch`, multi-catch, and `throws`. The file store uses `finally` to clean up. |
| Collections and generics | `LinkedHashMap<Integer, OrderLine>` keeps order lines in insertion order while allowing lookup by item ID. `ArrayList<Bill>` stores bill history. `HashMap<String, String>` holds request fields. |
| Comparator, lambdas and Stream API | `RestaurantService.report` filters bills by date, sorts with `Comparator`, and maps and sums totals with method references. |
| Concurrency and synchronization | Java’s four-thread executor handles requests. `synchronized` service methods serialize reads and writes so two requests cannot seat the same table. The test demonstrates this race. |
| Character streams and persistence | `FileReader`, `FileWriter`, buffered I/O, UTF-8 text, and try-with-resources in `RestaurantStore.java`. |

The project uses the syllabus topics that fit this workflow. The syllabus offers file or JDBC persistence, and this implementation uses the file option. The string pool, low-level thread lifecycle, and `wait/notify` are classroom concepts outside the app’s execution flow.

## Additional concepts for the viva

- **React state and components.** The screen is split into components such as `Floor`, `Menu`, and `Sales`. `useState` stores values that affect rendering, such as the active screen and the latest table snapshot. `useEffect` loads the initial state and refreshes a report when its date changes.
- **HTTP and JSON.** React calls Java with `fetch`. GET reads data. POST submits changes. Java returns a JSON response. The JDK’s built-in `HttpServer` receives requests. Vite builds React assets and forwards API calls during frontend development.
- **Java records.** A record is a concise Java class for carrying data. Java generates its constructor and accessors from the declared fields. For example, `bill.date()` reads the date. The service copies collections before exposing them through records.
- **Money and rounding.** Java stores ₹149.00 as the integer `14900` paise. `BigDecimal` converts menu price input to paise. Java rounds tax to the nearest paisa, adds it to the subtotal, then rounds the payable amount to the nearest rupee. Fractions of 50 paise or more round up. The signed difference appears as round-off.
- **Atomic file replacement.** The backend writes the entire next state to a temporary file, then moves it into place in one filesystem operation. Finalizing a bill saves it and changes the table to Billed in the same snapshot. Releasing the table changes it to Available. A failed save leaves the previous state in use. Keep the data file on a filesystem that supports atomic moves.

## Run the Java verification

From the project root on macOS or Linux:

```bash
mkdir -p backend/out
javac --release 17 -d backend/out backend/src/restaurant/*.java backend/tests/*.java
java -cp backend/out restaurant.RestaurantFlowTest
```

The test uses an independent temporary data file and checks invalid party size, concurrent assignment, menu changes, adding/updating/removing items, order price history, tax rounding, settlement, date filtering, and persistence after restart.
# Restaurant-Table-Management-Software
