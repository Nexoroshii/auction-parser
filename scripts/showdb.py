"""Быстрый просмотр базы AuctionNotifier (SQLite).

Запуск:
  py scripts/showdb.py            # сводка + последние лоты
  py scripts/showdb.py 20         # последние 20 лотов
  py scripts/showdb.py <lotId>    # полный details_json одного лота
"""
import sqlite3, os, sys, json

# Windows-консоль по умолчанию не UTF-8 — иначе кириллица/символы падают.
try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

DB = os.path.expanduser(r"~/.auctionnotifier/auctionnotifier.db")


def main():
    con = sqlite3.connect(DB)
    con.row_factory = sqlite3.Row
    arg = sys.argv[1] if len(sys.argv) > 1 else None

    # Детали конкретного лота
    if arg and not arg.isdigit() or (arg and len(arg) > 4 and arg.isdigit()):
        row = con.execute(
            "SELECT details_json FROM lots WHERE lot_id = ?", (arg,)).fetchone()
        if row and row["details_json"]:
            print(json.dumps(json.loads(row["details_json"]), ensure_ascii=False, indent=2))
        else:
            print(f"Лот {arg} не найден")
        return

    limit = int(arg) if arg and arg.isdigit() else 25

    print("=== Сводка ===")
    for r in con.execute("SELECT auction, COUNT(*) c, SUM(sent) sent "
                         "FROM lots GROUP BY auction"):
        print(f"  {r['auction']}: {r['c']} лотов, отправлено {r['sent'] or 0}")

    print(f"\n=== Последние {limit} лотов ===")
    q = ("SELECT lot_id, auction, sent, date_found, url "
         "FROM lots ORDER BY date_found DESC LIMIT ?")
    for r in con.execute(q, (limit,)):
        flag = "SENT" if r["sent"] else "new "
        print(f"  {flag} [{r['auction']:<6}] {r['lot_id']:<10} {r['date_found'][:19]}")
        print(f"       {r['url']}")

    print("\nПодробнее об одном лоте:  py scripts/showdb.py <lotId>")
    con.close()


if __name__ == "__main__":
    main()
