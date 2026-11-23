import React, { useEffect, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import './styles.css';

const currency = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR' });
const money = paise => currency.format(paise / 100);
const adjustment = paise => (paise > 0 ? '+' : '') + money(paise);
const tableName = id => 'Table ' + String(id).padStart(2, '0');
const billName = id => 'BILL-' + String(id).padStart(4, '0');
const prettyDate = date => new Intl.DateTimeFormat('en-IN', {
  weekday: 'long', day: 'numeric', month: 'long', year: 'numeric', timeZone: 'Asia/Kolkata'
}).format(new Date(date + 'T00:00:00+05:30'));

async function api(path, data) {
  const response = await fetch('/api/' + path, data === undefined ? undefined : {
    method: 'POST',
    headers: { 'X-Restaurant-Request': '1' },
    body: new URLSearchParams(data)
  });
  const content = await response.json();
  if (!response.ok) throw new Error(content.error || 'Request failed.');
  return content;
}

const navigation = [
  { name: 'Floor plan', icon: '▦' },
  { name: 'Menu', icon: '☷' },
  { name: 'Daily sales', icon: '▥' }
];

function App() {
  const [page, setPage] = useState('Floor plan');
  const [data, setData] = useState(null);
  const [selected, setSelected] = useState(1);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState(null);
  const [modal, setModal] = useState(null);
  const pending = useRef(false);

  async function load() {
    try {
      setData(await api('state'));
      setMessage(null);
    } catch (error) {
      setMessage({ error: true, text: 'Could not connect to the Java server. Check that it is running.' });
    }
  }

  useEffect(() => { load(); }, []);

  async function act(path, fields, success, options = {}) {
    if (pending.current) return;
    pending.current = true;
    setBusy(true);
    setMessage(null);
    try {
      const result = await api(path, fields);
      if (path === 'checkout') {
        setData(result.state);
        setModal({ type: 'bill', bill: result.bill });
      } else {
        setData(result);
        if (options.close) setModal(null);
      }
      if (success) setMessage({ text: success });
    } catch (error) {
      setMessage({ error: true, text: error.message });
    } finally {
      setBusy(false);
      pending.current = false;
    }
  }

  const table = data?.tables.find(entry => entry.id === selected);
  const showBill = bill => setModal({ type: 'bill', bill });
  const changePage = name => { setPage(name); setMessage(null); };
  const feedback = message && (
    <div className={message.error ? 'feedback error' : 'feedback'} role={message.error ? 'alert' : 'status'}>
      {message.text}
      <button className="icon-button" aria-label="Dismiss message" onClick={() => setMessage(null)}>×</button>
    </div>
  );

  return (
    <div className="app">
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-mark" aria-hidden="true"><i /><i /><i /><i /></div>
          <div>Table &amp; Till<small>Restaurant Manager</small></div>
        </div>
        <nav aria-label="Main navigation">
          {navigation.map(item => (
            <button key={item.name} className={page === item.name ? 'nav-button active' : 'nav-button'}
              aria-current={page === item.name ? 'page' : undefined} onClick={() => changePage(item.name)}>
              <span aria-hidden="true">{item.icon}</span>{item.name}
            </button>
          ))}
        </nav>
        <div className="sidebar-footer"><span className="status-dot" /> Local workspace</div>
      </aside>

      <main>
        <header className="topbar">
          <span><span className="top-icon" aria-hidden="true">◫</span> Restaurant workspace</span>
          <span className="date">{data ? prettyDate(data.today) : ''}</span>
        </header>

        <div className="content">
          <div className="page-title">
            <div>
              <h1>{page}</h1>
              <p>{{
                'Floor plan': 'Seat guests, manage orders, and close out tables.',
                'Menu': 'Manage the dishes and prices available to order.',
                'Daily sales': 'Review completed bills and totals by business date.'
              }[page]}</p>
            </div>
            {page === 'Menu' && data && (
              <button className="button primary" onClick={() => setModal({ type: 'menu' })}>＋ Add menu item</button>
            )}
          </div>
          {!modal && feedback}
          {!data && <div className="panel loading">
            {message ? <button className="button primary" onClick={load}>Reconnect</button> : 'Loading restaurant…'}
          </div>}
          {data && page === 'Floor plan' &&
            <Floor data={data} table={table} select={setSelected} busy={busy} act={act} setModal={setModal} />}
          {data && page === 'Menu' &&
            <Menu data={data} busy={busy} edit={item => setModal({ type: 'menu', item })}
              remove={item => setModal({ type: 'delete', item })} />}
          {data && page === 'Daily sales' && <Sales today={data.today} showBill={showBill} />}
        </div>
      </main>

      {modal && <Dialog close={() => { if (!pending.current) setModal(null); }}
        title={{ menu: modal.item ? 'Edit menu item' : 'Add menu item', order: 'Add to order',
          preview: 'Bill preview', bill: 'Itemized bill', delete: 'Delete menu item' }[modal.type]}>
        {feedback}
        {modal.type === 'menu' && <MenuForm item={modal.item} busy={busy}
          save={fields => act('menu/save', fields, 'Menu saved.', { close: true })} />}
        {modal.type === 'order' && <OrderPicker items={data.menu} busy={busy}
          add={(item, quantity) => act('order', { table: selected, item: item.id, quantity, add: true },
            item.name + ' added to the order.', { close: true })} />}
        {modal.type === 'delete' && <>
          <p>Delete {modal.item.name} from the menu? Existing orders and bills keep the item details.</p>
          <button className="button danger" disabled={busy}
            onClick={() => act('menu/delete', { id: modal.item.id }, 'Menu item deleted.', { close: true })}>Delete item</button>
        </>}
        {modal.type === 'preview' && <Receipt bill={table.order}>
          <div className="receipt-actions">
            <button className="button secondary" disabled={busy} onClick={() => setModal(null)}>Back to order</button>
            <button className="button primary" disabled={busy}
              onClick={() => act('checkout', { table: selected })}>Finalize bill</button>
          </div>
        </Receipt>}
        {modal.type === 'bill' && <Receipt bill={modal.bill} />}
      </Dialog>}
    </div>
  );
}

function tableStatus(table) {
  if (table.bill) return 'Billed';
  return table.order ? 'Occupied' : 'Available';
}

function TableBadge({ table }) {
  const status = tableStatus(table);
  return <span className={'badge ' + status.toLowerCase()}>{status}</span>;
}

function TableDiagram({ seats, party = 0, detailed = false }) {
  const diagram = (
    <div className="table-drawing" aria-hidden="true">
      {[0, 1].map(row => (
        <div className="chair-row" key={row}>
          {Array.from({ length: seats / 2 }, (_, chair) => (
            <span className={chair * 2 + row < party ? 'chair filled' : 'chair'} key={chair} />
          ))}
        </div>
      ))}
      <span className="tabletop" />
    </div>
  );

  if (!detailed) return diagram;

  return (
    <div className="seating-preview">
      {diagram}
      <p className="muted" aria-live="polite">{party} of {seats} seats filled</p>
    </div>
  );
}

function Floor({ data, table, select, busy, act, setModal }) {
  const [filter, setFilter] = useState('All tables');
  const [seatCount, setSeatCount] = useState(0);
  const capacities = [...new Set(data.tables.map(entry => entry.seats))].sort((a, b) => a - b);
  const occupied = data.tables.filter(entry => entry.order || entry.bill).length;
  const available = data.tables.length - occupied;
  const shown = data.tables.filter(entry => {
    const isOccupied = Boolean(entry.order || entry.bill);
    const matchesStatus = filter === 'All tables' || isOccupied === (filter === 'Occupied');
    return matchesStatus && (seatCount === 0 || entry.seats === seatCount);
  });
  const visit = table.order || table.bill;
  const viewTable = entry => {
    select(entry.id);
    if (window.matchMedia('(max-width: 820px)').matches) {
      document.getElementById('table-details')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
  };

  return <>
    <div className="stats">
      <Stat icon="▦" label="Total tables" value={data.tables.length} />
      <Stat icon="✓" label="Available" value={available} color="green" />
      <Stat icon="●" label="Occupied" value={occupied} color="amber" />
    </div>
    <div className="floor-layout">
      <section className="panel floor-panel">
        <div className="panel-heading"><h2>Dining room</h2><span className="muted">{shown.length} tables</span></div>
        <div className="floor-toolbar">
          <div className="filters" role="group" aria-label="Table status">
            {['All tables', 'Available', 'Occupied'].map(option =>
              <button key={option} aria-pressed={filter === option} onClick={() => setFilter(option)}>{option}</button>)}
          </div>
          <label className="seats-filter">
            Seats
            <select value={seatCount} onChange={event => setSeatCount(Number(event.target.value))}>
              <option value={0}>Any size</option>
              {capacities.map(capacity => <option key={capacity} value={capacity}>{capacity} seats</option>)}
            </select>
          </label>
        </div>
        <div className="table-grid">
          {shown.map(entry => {
            const group = entry.order || entry.bill;
            return (
              <button className={'table-card' + (table.id === entry.id ? ' selected' : '')}
                key={entry.id} aria-pressed={table.id === entry.id} onClick={() => viewTable(entry)}>
                <div className="table-card-top"><span>{tableName(entry.id)}</span>
                  <TableBadge table={entry} />
                </div>
                <TableDiagram seats={entry.seats} party={group?.party} />
                <div className="table-foot">
                  {group ? group.guest + ' · ' + group.party + '/' + entry.seats + ' seats' : entry.seats + ' seats'}
                </div>
              </button>
            );
          })}
        </div>
        {!shown.length && <div className="empty">No tables match these filters.</div>}
      </section>

      <section className="panel detail-panel" id="table-details">
        <div className="panel-heading">
          <h2>{tableName(table.id)}</h2>
          <TableBadge table={table} />
        </div>
        <p className="detail-caption">{visit
          ? visit.guest + ' · ' + visit.party + (visit.party === 1 ? ' guest' : ' guests')
          : table.seats + '-seat table'}</p>
        {visit && <TableDiagram seats={table.seats} party={visit.party} detailed />}
        {table.order && <ActiveOrder table={table} busy={busy} act={act} setModal={setModal} />}
        {table.bill && <div className="stack active-order">
          <p className="muted">Release the table when the guests leave.</p>
          <button className="button secondary" onClick={() => setModal({ type: 'bill', bill: table.bill })}>
            View {billName(table.bill.id)}
          </button>
          <button className="button primary" disabled={busy}
            onClick={() => act('release', { table: table.id }, tableName(table.id) + ' is available.')}>
            Release table
          </button>
        </div>}
        {!visit && <SeatForm key={table.id} table={table} busy={busy} act={act} />}
      </section>
    </div>
  </>;
}

function Stat({ icon, label, value, color = '' }) {
  return <div className="panel stat">
    <span className={'stat-icon ' + color} aria-hidden="true">{icon}</span>
    <div><span className="muted">{label}</span><strong>{value}</strong></div>
  </div>;
}

function SeatForm({ table, busy, act }) {
  const [party, setParty] = useState(1);

  function submit(event) {
    event.preventDefault();
    const fields = Object.fromEntries(new FormData(event.currentTarget));
    act('assign', { ...fields, table: table.id }, 'Guests seated at ' + tableName(table.id) + '.');
  }

  return <form className="stack seat-form" onSubmit={submit}>
    <h3>Seat guests</h3>
    <label>Guest name<input name="guest" placeholder="e.g. Sharma family" maxLength="60" required /></label>
    <label>Party size<select name="party" value={party} onChange={event => setParty(Number(event.target.value))}>
      {Array.from({ length: table.seats }, (_, index) => <option key={index} value={index + 1}>
        {index + 1} {index === 0 ? 'guest' : 'guests'}
      </option>)}
    </select></label>
    <TableDiagram seats={table.seats} party={party} detailed />
    <button className="button primary" disabled={busy}>Assign table <span aria-hidden="true">→</span></button>
  </form>;
}

function ActiveOrder({ table, busy, act, setModal }) {
  const order = table.order;
  function quantity(line, value) {
    act('order', { table: table.id, item: line.itemId, quantity: value });
  }
  return <div className="stack active-order">
    <div className="order-heading"><h3>Current order</h3>
      <button className="button soft small" disabled={busy} onClick={() => setModal({ type: 'order' })}>＋ Add item</button>
    </div>
    {!order.lines.length && <div className="empty compact">No items ordered yet.</div>}
    {order.lines.map(line => <div className="order-line" key={line.itemId}>
      <div className="line-description"><strong>{line.name}</strong><span>{money(line.total)}</span></div>
      <div className="stepper">
        <button aria-label={'Decrease ' + line.name} disabled={busy || line.quantity <= 1}
          onClick={() => quantity(line, line.quantity - 1)}>−</button>
        <span>{line.quantity}</span>
        <button aria-label={'Increase ' + line.name} disabled={busy || line.quantity >= 99}
          onClick={() => quantity(line, line.quantity + 1)}>＋</button>
      </div>
      <button className="icon-button remove" aria-label={'Remove ' + line.name} disabled={busy}
        onClick={() => quantity(line, 0)}>×</button>
    </div>)}
    <Totals entry={order} />
    <button className="button primary" disabled={busy || !order.lines.length}
      onClick={() => setModal({ type: 'preview' })}>Preview bill <span aria-hidden="true">→</span></button>
    {!order.lines.length && <button className="button secondary" disabled={busy}
      onClick={() => act('release', { table: table.id }, tableName(table.id) + ' is available.')}>Release table</button>}
  </div>;
}

function Totals({ entry }) {
  return <dl className="totals">
    <div><dt>Subtotal</dt><dd>{money(entry.subtotal)}</dd></div>
    <div><dt>Tax ({entry.taxRate}%)</dt><dd>{money(entry.tax)}</dd></div>
    {entry.roundOff !== 0 && <div><dt>Round-off</dt><dd>{adjustment(entry.roundOff)}</dd></div>}
    <div className="grand-total"><dt>Amount payable</dt><dd>{money(entry.total)}</dd></div>
  </dl>;
}

function Menu({ data, busy, edit, remove }) {
  const [search, setSearch] = useState('');
  const categories = [...new Set(data.menu.map(item => item.category))].sort();
  const [category, setCategory] = useState('');
  const selectedCategory = categories.includes(category) ? category : '';
  const menu = data.menu.filter(item => (!selectedCategory || item.category === selectedCategory) &&
    (item.name + ' ' + item.category).toLowerCase().includes(search.trim().toLowerCase()));
  return <section className="panel list-panel">
    <div className="list-toolbar">
      <input aria-label="Search menu" placeholder="Search menu…" type="search" value={search}
        onChange={event => setSearch(event.target.value)} />
      <select aria-label="Filter by category" value={selectedCategory} onChange={event => setCategory(event.target.value)}>
        <option value="">All categories</option>
        {categories.map(value => <option key={value}>{value}</option>)}
      </select>
    </div>
    <div className="table-scroll">
      <table>
        <thead><tr><th>Item</th><th>Category</th><th>Price</th><th>Status</th><th>Actions</th></tr></thead>
        <tbody>{menu.map(item => <tr key={item.id}>
          <td><strong>{item.name}</strong></td><td>{item.category}</td><td>{money(item.price)}</td>
          <td><span className={item.available ? 'badge' : 'badge neutral'}>{item.available ? 'Available' : 'Unavailable'}</span></td>
          <td className="row-actions">
            <button className="button soft small" disabled={busy} onClick={() => edit(item)}>Edit</button>
            <button className="button secondary small" disabled={busy} onClick={() => remove(item)}>Delete</button>
          </td>
        </tr>)}</tbody>
      </table>
    </div>
    {!menu.length && <div className="empty">No menu items match your filters.</div>}
  </section>;
}

function MenuForm({ item, save, busy }) {
  return <form className="stack" onSubmit={event => {
    event.preventDefault();
    const fields = new FormData(event.currentTarget);
    save({ ...Object.fromEntries(fields), id: item?.id ?? 0, available: fields.has('available') });
  }}>
    <label>Item name<input name="name" maxLength="60" defaultValue={item?.name} required autoFocus /></label>
    <label>Category<input name="category" maxLength="60" defaultValue={item?.category} required /></label>
    <label>Price (₹)<input name="price" type="number" min="0.01" max="999999.99" step="0.01"
      defaultValue={item ? item.price / 100 : ''} required /></label>
    <label className="checkbox"><input name="available" type="checkbox" defaultChecked={item?.available ?? true} /> Available to order</label>
    <button className="button primary" disabled={busy}>Save item</button>
  </form>;
}

function OrderPicker({ items, busy, add }) {
  const [search, setSearch] = useState('');
  const [quantity, setQuantity] = useState('1');
  const available = items.filter(item => item.available &&
    (item.name + ' ' + item.category).toLowerCase().includes(search.trim().toLowerCase()));
  const valid = /^\d+$/.test(quantity) && Number(quantity) >= 1 && Number(quantity) <= 99;
  return <div className="stack">
    <div className="picker-controls">
      <label>Search menu<input type="search" value={search} onChange={event => setSearch(event.target.value)} autoFocus /></label>
      <label>Quantity<input type="number" min="1" max="99" step="1" value={quantity}
        onChange={event => setQuantity(event.target.value)} /></label>
    </div>
    <div className="picker-list">
      {available.map(item => <div className="picker-item" key={item.id}>
        <div><strong>{item.name}</strong><small>{item.category} · {money(item.price)}</small></div>
        <button className="button soft small" disabled={busy || !valid} aria-label={'Add ' + item.name}
          onClick={() => add(item, quantity)}>＋ Add</button>
      </div>)}
      {!available.length && <div className="empty compact">No available items match your search.</div>}
    </div>
  </div>;
}

function Sales({ today, showBill }) {
  const [date, setDate] = useState(today);
  const [report, setReport] = useState(null);
  const [error, setError] = useState('');
  useEffect(() => {
    if (!date) return undefined;
    let current = true;
    setError('');
    setReport(null);
    api('report?date=' + encodeURIComponent(date))
      .then(result => { if (current) setReport(result); })
      .catch(failure => { if (current) setError(failure.message); });
    return () => { current = false; };
  }, [date]);

  return <>
    <label className="date-filter">Business date<input type="date" max={today} value={date}
      onChange={event => setDate(event.target.value)} /></label>
    {error && <div className="feedback error" role="alert">{error}</div>}
    {date && !report && !error && <div className="panel loading">Loading report…</div>}
    {date && report?.date === date && <>
      <div className="stats sales-stats">
        <Stat icon="₹" label="Gross sales" value={money(report.total)} color="green" />
        <Stat icon="▤" label="Completed bills" value={report.count} />
        <Stat icon="●" label="Guests served" value={report.covers} color="amber" />
      </div>
      <section className="panel list-panel">
        <div className="list-toolbar report-toolbar"><h2>Completed bills</h2>
          <span className="muted">Net sales {money(report.subtotal)} · Tax {money(report.tax)}
            {report.roundOff !== 0 && ' · Round-off ' + adjustment(report.roundOff)}</span></div>
        {report.bills.length ? <div className="table-scroll"><table>
          <thead><tr><th>Bill</th><th>Table</th><th>Guest</th><th>Tax</th><th>Total</th><th>Details</th></tr></thead>
          <tbody>{report.bills.map(bill => <tr key={bill.id}>
            <td><strong>{billName(bill.id)}</strong></td><td>{tableName(bill.table)}</td><td>{bill.guest}</td>
            <td>{money(bill.tax)}</td><td>{money(bill.total)}</td>
            <td><button className="button soft small" onClick={() => showBill(bill)}>View bill</button></td>
          </tr>)}</tbody>
        </table></div> : <div className="empty">No bills completed on this date.</div>}
      </section>
    </>}
  </>;
}

function Dialog({ title, close, children }) {
  const ref = useRef(null);
  useEffect(() => {
    const dialog = ref.current;
    dialog.showModal();
    return () => dialog.close();
  }, []);
  return <dialog ref={ref} aria-labelledby="dialog-title" onCancel={event => { event.preventDefault(); close(); }}>
    <div className="dialog-header"><h2 id="dialog-title">{title}</h2>
      <button className="icon-button" aria-label="Close dialog" onClick={close}>×</button></div>
    {children}
  </dialog>;
}

function Receipt({ bill, children }) {
  return <div className="receipt stack">
    <div className="receipt-meta">
      {bill.id > 0 && <strong>{billName(bill.id)}</strong>}
      <span>{prettyDate(bill.date)}</span>
      <span>{tableName(bill.table)} · {bill.guest} · {bill.party} {bill.party === 1 ? 'guest' : 'guests'}</span>
    </div>
    <div className="table-scroll"><table>
      <thead><tr><th>Item</th><th>Qty</th><th>Unit price</th><th>Total</th></tr></thead>
      <tbody>{bill.lines.map(line => <tr key={line.itemId}>
        <td>{line.name}</td><td>{line.quantity}</td><td>{money(line.price)}</td><td>{money(line.total)}</td>
      </tr>)}</tbody>
    </table></div>
    <Totals entry={bill} />
    {children ?? <button className="button primary print-button" onClick={() => window.print()}>Print bill</button>}
  </div>;
}

createRoot(document.getElementById('root')).render(<App />);
