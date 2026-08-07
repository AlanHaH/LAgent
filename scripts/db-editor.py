#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""知序 · 可视化数据库编辑工具（本地测试/演示用）

功能：
  - 浏览任意表（分页），双击单元格改值、新增行、删除行
  - 快捷操作：把某目标未完成任务挪到今天、把目标起止日期改为今天起 N 天
  - 自定义 SQL 执行（SELECT 出结果，DML 提示影响行数）

用法：
  cd ai-service
  .venv/Scripts/python.exe -m pip install pymysql        # 一次性
  .venv/Scripts/python.exe ../scripts/db-editor.py

说明：数据库时间统一存 UTC（本地 09:00 = UTC 01:00）。
"""
import os
import re
import sys
import tkinter as tk
from tkinter import ttk, messagebox, simpledialog

try:
    import pymysql
except ImportError:
    print("缺少 pymysql：请先执行  .\\.venv\\Scripts\\python.exe -m pip install pymysql")
    sys.exit(1)

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PAGE_SIZE = 100


def load_env():
    """从仓库根 .env 读取连接信息（不落库、不打印密码）。"""
    env = {}
    path = os.path.join(REPO_ROOT, ".env")
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    env[k.strip()] = v.strip().strip('"').strip("'")
    return env


def pick(row, *names):
    """information_schema 的列名在部分 MySQL 上返回大写（TABLE_NAME），兼容两种大小写。"""
    for name in names:
        if name in row:
            return row[name]
    return None


class DbEditorApp:
    def __init__(self, root):
        self.root = root
        root.title("知序 · 数据库编辑器")
        root.geometry("1280x820")
        root.minsize(1000, 640)

        env = load_env()
        self.conn = None
        self.table = None
        self.page = 0
        self.columns = []
        self.primary_key = None
        self.rows = []

        # ---- 连接栏 ----
        bar = ttk.Frame(root, padding=(10, 8))
        bar.pack(fill="x")
        ttk.Label(bar, text="主机").pack(side="left")
        self.var_host = tk.StringVar(value=env.get("MYSQL_HOST", "localhost"))
        ttk.Entry(bar, textvariable=self.var_host, width=14).pack(side="left", padx=(2, 10))
        ttk.Label(bar, text="端口").pack(side="left")
        self.var_port = tk.StringVar(value=env.get("MYSQL_PORT", "3306"))
        ttk.Entry(bar, textvariable=self.var_port, width=6).pack(side="left", padx=(2, 10))
        ttk.Label(bar, text="用户").pack(side="left")
        self.var_user = tk.StringVar(value=env.get("MYSQL_USER", "learning"))
        ttk.Entry(bar, textvariable=self.var_user, width=12).pack(side="left", padx=(2, 10))
        ttk.Label(bar, text="密码").pack(side="left")
        self.var_password = tk.StringVar(value=env.get("MYSQL_PASSWORD", ""))
        ttk.Entry(bar, textvariable=self.var_password, width=16, show="*").pack(side="left", padx=(2, 10))
        ttk.Label(bar, text="数据库").pack(side="left")
        self.var_db = tk.StringVar(value=env.get("MYSQL_DATABASE", "adaptive_learning"))
        ttk.Entry(bar, textvariable=self.var_db, width=18).pack(side="left", padx=(2, 10))
        self.btn_connect = ttk.Button(bar, text="连接", command=self.connect_db)
        self.btn_connect.pack(side="left", padx=6)

        # ---- 主体：左表列表 / 右数据 ----
        main = ttk.Panedwindow(root, orient="horizontal")
        main.pack(fill="both", expand=True, padx=10)

        left = ttk.Frame(main)
        main.add(left, weight=1)
        ttk.Label(left, text="表列表（双击加载）").pack(anchor="w", padx=4, pady=(4, 2))
        self.var_filter = tk.StringVar()
        ttk.Entry(left, textvariable=self.var_filter).pack(fill="x", padx=4, pady=(0, 4))
        self.var_filter.trace_add("write", lambda *_: self.load_tables())
        self.tree_tables = ttk.Treeview(left, show="tree", height=40)
        self.tree_tables.pack(fill="both", expand=True, padx=4, pady=4)
        self.tree_tables.bind("<Double-1>", lambda _: self.select_table())

        right = ttk.Frame(main)
        main.add(right, weight=4)

        # 表名 + 分页
        head = ttk.Frame(right)
        head.pack(fill="x", padx=4, pady=(4, 2))
        self.lbl_table = ttk.Label(head, text="未连接")
        self.lbl_table.pack(side="left")
        self.btn_prev = ttk.Button(head, text="← 上一页", command=lambda: self.goto(self.page - 1))
        self.btn_prev.pack(side="right", padx=2)
        self.lbl_page = ttk.Label(head, text="")
        self.lbl_page.pack(side="right", padx=8)
        self.btn_next = ttk.Button(head, text="下一页 →", command=lambda: self.goto(self.page + 1))
        self.btn_next.pack(side="right")

        # 数据表格
        self.tree = ttk.Treeview(right, show="headings")
        vsb = ttk.Scrollbar(right, orient="vertical", command=self.tree.yview)
        self.tree.configure(yscrollcommand=vsb.set)
        self.tree.pack(side="left", fill="both", expand=True, padx=(4, 0))
        vsb.pack(side="left", fill="y", padx=(0, 4))
        self.tree.bind("<Double-1>", self.on_row_double_click)

        # 行操作
        rowbar = ttk.Frame(right)
        rowbar.pack(fill="x", padx=4, pady=4)
        ttk.Button(rowbar, text="➕ 新增行", command=self.add_row).pack(side="left", padx=2)
        ttk.Button(rowbar, text="🗑 删除选中行", command=self.delete_row).pack(side="left", padx=2)
        ttk.Button(rowbar, text="🔄 刷新", command=self.load_page).pack(side="left", padx=8)
        ttk.Label(rowbar, text="双击单元格修改值 · 时间按 UTC 存储").pack(side="left", padx=8)

        # 快捷操作
        quick = ttk.LabelFrame(right, text="快捷操作（测试排期用）", padding=8)
        quick.pack(fill="x", padx=4, pady=4)
        ttk.Label(quick, text="目标 publicId：").pack(side="left")
        self.var_goal = tk.StringVar()
        ttk.Entry(quick, textvariable=self.var_goal, width=24).pack(side="left", padx=(0, 10))
        ttk.Button(quick, text="任务挪到今天（前 N 个）", command=self.move_tasks_to_today).pack(side="left", padx=2)
        ttk.Button(quick, text="目标起止改到今天起 N 天", command=self.adjust_goal_dates).pack(side="left", padx=2)
        ttk.Label(quick, text="（时间从现在起每 2 小时一个，每任务 90 分钟）").pack(side="left", padx=6)

        # SQL 控制台
        sqlbox = ttk.LabelFrame(root, text="自定义 SQL（非 SELECT 会先确认）", padding=8)
        sqlbox.pack(fill="x", padx=10, pady=(0, 6))
        self.txt_sql = tk.Text(sqlbox, height=3, font=("Consolas", 10))
        self.txt_sql.pack(fill="x")
        ttk.Button(sqlbox, text="执行", command=self.run_sql).pack(anchor="e", pady=(4, 0))

        # 状态栏
        self.status = ttk.Label(root, text="未连接", anchor="w", relief="sunken")
        self.status.pack(fill="x", side="bottom")

        self.connect_db()

    # ---------- 连接 ----------
    def connect_db(self):
        try:
            if self.conn:
                self.conn.close()
            self.conn = pymysql.connect(
                host=self.var_host.get().strip() or "localhost",
                port=int(self.var_port.get().strip() or 3306),
                user=self.var_user.get().strip(),
                password=self.var_password.get(),
                database=self.var_db.get().strip(),
                charset="utf8mb4",
                cursorclass=pymysql.cursors.DictCursor,
                autocommit=True,
            )
            self.set_status("已连接 ✓")
            self.btn_connect.config(text="重新连接")
            self.load_tables()
        except Exception as e:
            self.set_status(f"连接失败：{e}")

    def cursor(self):
        return self.conn.cursor()

    def set_status(self, text):
        self.status.config(text=text)

    # ---------- 表列表 ----------
    def load_tables(self):
        if not self.conn:
            return
        f = self.var_filter.get().strip().lower()
        try:
            with self.cursor() as cur:
                cur.execute("""
                    SELECT table_name, table_rows FROM information_schema.tables
                    WHERE table_schema=%s ORDER BY table_name""", (self.var_db.get().strip(),))
                tables = [(r["table_name"], r["table_rows"]) for r in cur.fetchall()]
        except Exception as e:
            self.set_status(f"读取表失败：{e}")
            return
        self.tree_tables.delete(*self.tree_tables.get_children())
        for row in tables:
            name = pick(row, "table_name", "TABLE_NAME")
            count = pick(row, "table_rows", "TABLE_ROWS")
            if not name or (f and f not in name.lower()):
                continue
            count_text = f"{int(count):,}" if count is not None else "?"
            self.tree_tables.insert("", "end", iid=name, text=f"{name}  ({count_text} 行)")

    def select_table(self):
        sel = self.tree_tables.selection()
        if not sel:
            return
        self.table = sel[0]
        self.page = 0
        try:
            with self.cursor() as cur:
                cur.execute("DESCRIBE `%s`" % self.table.replace("`", "``"))
                self.columns = [pick(r, "Field", "field") for r in cur.fetchall()]
                cur.execute("""SELECT column_name FROM information_schema.columns
                    WHERE table_schema=%s AND table_name=%s AND column_key='PRI'""",
                            (self.var_db.get().strip(), self.table))
                pri = cur.fetchone()
                self.primary_key = pick(pri, "column_name", "COLUMN_NAME") if pri else None
        except Exception as e:
            self.set_status(f"读取表结构失败：{e}")
            return
        self.tree.delete(*self.tree.get_children())
        self.tree["columns"] = self.columns
        widths = {c: 120 for c in self.columns}
        for c in self.columns:
            self.tree.heading(c, text=c)
            self.tree.column(c, width=min(widths.get(c, 120), 260), anchor="w")
        self.lbl_table.config(text=f"表：{self.table}（主键：{self.primary_key or '无'}）")
        self.load_page()

    # ---------- 数据分页 ----------
    def goto(self, page):
        if page < 0:
            return
        self.page = page
        self.load_page()

    def load_page(self):
        if not self.conn or not self.table:
            return
        try:
            with self.cursor() as cur:
                cur.execute(f"SELECT * FROM `{self.table.replace('`', '``')}` LIMIT %s OFFSET %s",
                            (PAGE_SIZE, self.page * PAGE_SIZE))
                self.rows = cur.fetchall()
                cur.execute(f"SELECT COUNT(*) AS n FROM `{self.table.replace('`', '``')}`")
                total = cur.fetchone()["n"]
        except Exception as e:
            self.set_status(f"读取数据失败：{e}")
            return
        self.tree.delete(*self.tree.get_children())
        for row in self.rows:
            self.tree.insert("", "end", values=[row.get(c) for c in self.columns])
        self.lbl_page.config(text=f"{self.page * PAGE_SIZE + 1}–{self.page * PAGE_SIZE + len(self.rows)} / {total:,}")
        self.btn_prev.config(state="normal" if self.page > 0 else "disabled")
        self.set_status(f"已加载 {self.table} 第 {self.page + 1} 页")

    def _selected_row(self):
        sel = self.tree.selection()
        if not sel:
            messagebox.showinfo("提示", "请先选中一行")
            return None
        return self.rows[self.tree.index(sel[0])]

    # ---------- 编辑 ----------
    def on_row_double_click(self, event):
        region = self.tree.identify("region", event.x, event.y)
        if region != "cell":
            return
        col_index = int(self.tree.identify_column(event.x)[1:]) - 1
        row = self._selected_row()
        if not row or self.primary_key is None:
            return
        col = self.columns[col_index]
        current = row.get(col)
        dialog = tk.Toplevel(self.root)
        dialog.title(f"修改 {self.table}.{col}（主键 {self.primary_key}={row.get(self.primary_key)}）")
        dialog.geometry("520x140")
        ttk.Label(dialog, text="值（留空 = NULL；时间按 UTC 传，如 2026-08-01 09:00:00）").pack(anchor="w", padx=12, pady=(12, 4))
        var = tk.StringVar(value="" if current is None else str(current))
        entry = ttk.Entry(dialog, textvariable=var, width=60)
        entry.pack(padx=12, fill="x")
        entry.focus_set()

        def save():
            value = var.get().strip()
            try:
                with self.cursor() as cur:
                    cur.execute(
                        f"UPDATE `{self.table.replace('`', '``')}` SET `{col.replace('`', '``')}`=%s "
                        f"WHERE `{self.primary_key.replace('`', '``')}`=%s",
                        (None if value == "" else value, row.get(self.primary_key)))
            except Exception as e:
                messagebox.showerror("保存失败", str(e))
                return
            dialog.destroy()
            self.load_page()
            self.set_status(f"已更新 {self.table}：{col} = {value or 'NULL'}")

        ttk.Button(dialog, text="保存", command=save).pack(pady=6)
        entry.bind("<Return>", lambda _: save())

    def add_row(self):
        if not self.table or self.primary_key is None:
            messagebox.showinfo("提示", "请先选择一张表")
            return
        dialog = tk.Toplevel(self.root)
        dialog.title(f"新增行：{self.table}")
        dialog.geometry("640x420")
        vars_ = {}
        frame = ttk.Frame(dialog)
        frame.pack(fill="both", expand=True, padx=12, pady=8)
        canvas = tk.Canvas(frame)
        sb = ttk.Scrollbar(frame, orient="vertical", command=canvas.yview)
        inner = ttk.Frame(canvas)
        inner.bind("<Configure>", lambda e: canvas.configure(scrollregion=canvas.bbox("all")))
        canvas.create_window((0, 0), window=inner, anchor="nw")
        canvas.configure(yscrollcommand=sb.set)
        canvas.pack(side="left", fill="both", expand=True)
        sb.pack(side="right", fill="y")
        for i, col in enumerate(self.columns):
            ttk.Label(inner, text=col).grid(row=i, column=0, sticky="w", padx=4, pady=2)
            var = tk.StringVar(value="NULL" if i == 0 else "")
            vars_[col] = var
            ttk.Entry(inner, textvariable=var, width=50).grid(row=i, column=1, padx=4, pady=2)

        def save():
            cols, vals = [], []
            for col, var in vars_.items():
                v = var.get().strip()
                if v == "":
                    continue
                cols.append(f"`{col.replace('`', '``')}`")
                vals.append(None if v == "NULL" else v)
            if not cols:
                messagebox.showinfo("提示", "至少填写一列")
                return
            placeholders = ", ".join(["%s"] * len(vals))
            try:
                with self.cursor() as cur:
                    cur.execute(
                        f"INSERT INTO `{self.table.replace('`', '``')}` ({', '.join(cols)}) VALUES ({placeholders})", vals)
            except Exception as e:
                messagebox.showerror("新增失败", str(e))
                return
            dialog.destroy()
            self.load_page()
            self.set_status("已新增一行")

        ttk.Button(dialog, text="插入", command=save).pack(pady=6)

    def delete_row(self):
        row = self._selected_row()
        if not row or self.primary_key is None:
            return
        pk = row.get(self.primary_key)
        if not messagebox.askyesno("确认删除", f"删除 {self.table} 主键 = {pk} 的这一行？"):
            return
        try:
            with self.cursor() as cur:
                cur.execute(f"DELETE FROM `{self.table.replace('`', '``')}` WHERE `{self.primary_key.replace('`', '``')}`=%s", (pk,))
        except Exception as e:
            messagebox.showerror("删除失败", str(e))
            return
        self.load_page()
        self.set_status(f"已删除主键 {pk}")

    # ---------- 快捷操作 ----------
    def move_tasks_to_today(self):
        goal_pid = self.var_goal.get().strip()
        if not goal_pid:
            messagebox.showinfo("提示", "先填写目标 publicId（目标页 URL 或列表里的编号）")
            return
        n = simpledialog.askinteger("任务挪到今天", "挪前几个未完成任务到今天？", initialvalue=3, minvalue=1, maxvalue=10)
        if not n:
            return
        try:
            with self.cursor() as cur:
                cur.execute("SELECT id, name FROM learning_goal WHERE public_id=%s AND deleted_at IS NULL", (goal_pid,))
                goal = cur.fetchone()
                if not goal:
                    messagebox.showerror("未找到", f"没有 public_id={goal_pid} 的目标")
                    return
                cur.execute("""SELECT id, title, scheduled_start FROM learning_task
                    WHERE goal_id=%s AND lifecycle_status='NOT_STARTED' AND deleted_at IS NULL
                    ORDER BY scheduled_start LIMIT %s""", (goal["id"], n))
                tasks = cur.fetchall()
                if not tasks:
                    messagebox.showinfo("没有任务", "该目标没有未开始的任务")
                    return
                for i, task in enumerate(tasks):
                    cur.execute("""UPDATE learning_task
                        SET scheduled_start=UTC_TIMESTAMP() + INTERVAL %s MINUTE,
                            due_at=UTC_TIMESTAMP() + INTERVAL %s MINUTE
                        WHERE id=%s""", (i * 120, i * 120 + 90, task["id"]))
        except Exception as e:
            messagebox.showerror("失败", str(e))
            return
        messagebox.showinfo("完成", f"已把 {len(tasks)} 个任务挪到今天（每 2 小时一个）")
        self.set_status("任务已挪到今天")
        if self.table == "learning_task":
            self.load_page()

    def adjust_goal_dates(self):
        goal_pid = self.var_goal.get().strip()
        if not goal_pid:
            messagebox.showinfo("提示", "先填写目标 publicId")
            return
        n = simpledialog.askinteger("目标周期", "今天起多少天结束？", initialvalue=30, minvalue=1, maxvalue=366)
        if not n:
            return
        try:
            with self.cursor() as cur:
                cur.execute("UPDATE learning_goal SET start_date=CURDATE(), due_date=CURDATE() + INTERVAL %s DAY WHERE public_id=%s AND deleted_at IS NULL", (n, goal_pid))
        except Exception as e:
            messagebox.showerror("失败", str(e))
            return
        messagebox.showinfo("完成", f"目标已改为今天起 {n} 天")
        self.set_status("目标日期已调整")

    # ---------- SQL ----------
    def run_sql(self):
        sql = self.txt_sql.get("1.0", "end").strip()
        if not sql or not self.conn:
            return
        if not sql.rstrip(";").lstrip().lower().startswith("select"):
            if not messagebox.askyesno("确认执行", "这不是 SELECT，会修改数据库：\n\n" + sql[:300]):
                return
        try:
            with self.cursor() as cur:
                cur.execute(sql)
                if cur.description:
                    rows = cur.fetchall()
                    cols = [d[0] for d in cur.description]
                    self._show_sql_result(cols, rows)
                    self.set_status(f"查询返回 {len(rows)} 行")
                else:
                    self.set_status(f"执行成功，影响 {cur.rowcount} 行")
                    if self.table:
                        self.load_page()
        except Exception as e:
            messagebox.showerror("SQL 错误", str(e))

    def _show_sql_result(self, cols, rows):
        win = tk.Toplevel(self.root)
        win.title("SQL 结果")
        win.geometry("900x480")
        tree = ttk.Treeview(win, show="headings")
        tree["columns"] = cols
        for c in cols:
            tree.heading(c, text=c)
            tree.column(c, width=120, anchor="w")
        for r in rows:
            tree.insert("", "end", values=[r.get(c) for c in cols])
        tree.pack(fill="both", expand=True, padx=8, pady=8)


def main():
    root = tk.Tk()
    DbEditorApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()
