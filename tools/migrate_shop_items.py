#!/usr/bin/env python3
"""把旧版 shop_items 配置迁移成 1.0.5 的 in/ 与 out/ 两份配置。

默认只预览（dry-run），不改动任何文件；加 --apply 才会真正写入。
脚本永不删除 / 修改 / 移动源文件：旧的 shop_items/ 目录和 shop_items.json 会原样保留，
方便回滚或人工核对。

用法
----
  python tools/migrate_shop_items.py                       # 预览 run/config/better-minecraft-shop
  python tools/migrate_shop_items.py --config-dir <dir>     # 指定配置目录
  python tools/migrate_shop_items.py --apply                # 真正写入 in/ 与 out/
  python tools/migrate_shop_items.py --apply --overwrite     # 允许覆盖目标里已有的同名文件

迁移规则（与 1.0.5 运行时校验一致）
----------------------------------
  * price >= 1        -> in/<原文件名>.json   {item, price, stock}
  * sell_price >= 1   -> out/<原文件名>.json  {item, sell_price}
  * 同一物品两边都有 -> 两边各写一条；只有一边有的只出现在那一边。
  * components 只保留 minecraft:enchantments / minecraft:stored_enchantments，
    其它组件会被丢掉并提示（运行时也只认这两类）。
  * sell_price > price 的条目会被压到 price（与运行时防刷钱逻辑一致）并提示。
  * 分类的 id / display_name / icon / priority 原样搬运。
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

ENCHANTMENT_KEYS = ("minecraft:enchantments", "minecraft:stored_enchantments")
NEW_FORMAT_VERSION = 2
UNLIMITED = -1


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="把旧版 shop_items 配置迁移到 in/ 与 out/（默认只预览）")
    parser.add_argument("--config-dir", type=Path, default=Path("run/config/better-minecraft-shop"),
                        help="配置目录，默认 run/config/better-minecraft-shop")
    parser.add_argument("--apply", action="store_true", help="真正写入（默认只预览）")
    parser.add_argument("--overwrite", action="store_true", help="允许覆盖目标目录里已存在的同名文件")
    return parser.parse_args(argv)


def iter_sources(config_dir: Path) -> list[Path]:
    """旧版可以是一个目录（可递归）加一个单文件，两个都读。"""
    sources: list[Path] = []
    legacy_dir = config_dir / "shop_items"
    if legacy_dir.is_dir():
        sources.extend(sorted(p for p in legacy_dir.rglob("*.json") if p.is_file()))
    legacy_file = config_dir / "shop_items.json"
    if legacy_file.is_file():
        sources.append(legacy_file)
    return sources


def read_doc(path: Path) -> dict[str, Any] | None:
    try:
        raw = path.read_text(encoding="utf-8-sig")
    except OSError as exc:
        print(f"  ! 读不了 {path}：{exc}")
        return None
    try:
        doc = json.loads(raw)
    except json.JSONDecodeError as exc:
        print(f"  ! {path} 不是合法 JSON（第 {exc.lineno} 行第 {exc.colno} 列）：{exc.msg}")
        return None
    if not isinstance(doc, dict):
        print(f"  ! {path} 顶层不是对象，已跳过")
        return None
    version = doc.get("version")
    if version is not None and version != 1:
        print(f"  ! {path} 的 version={version!r}，本脚本按 1 处理")
    return doc


def copy_category_meta(src: dict[str, Any]) -> dict[str, Any]:
    """保留分类的展示字段，items 由调用方填入。"""
    out: dict[str, Any] = {"id": str(src.get("id") or "").strip().lower()}
    for key in ("display_name", "icon", "priority"):
        if key in src:
            out[key] = src[key]
    return out


def clean_components(item: dict[str, Any], where: str) -> dict[str, Any] | None:
    components = item.get("components")
    if components is None:
        return None
    if not isinstance(components, dict):
        print(f"  ! {where} 的 components 不是对象，已忽略")
        return None
    kept = {k: v for k, v in components.items() if k in ENCHANTMENT_KEYS}
    dropped = sorted(set(components) - set(kept))
    if dropped:
        print(f"  ! {where} 丢弃不受支持的组件：{', '.join(dropped)}")
    return kept or None


def convert(sources: list[Path], config_dir: Path) -> tuple[dict[str, dict], dict[str, dict], list[str]]:
    """返回 (in 文件表, out 文件表, 提示信息)。文件表 = 文件名 -> 文档。"""
    in_files: dict[str, dict] = {}
    out_files: dict[str, dict] = {}
    notes: list[str] = []
    legacy_dir = config_dir / "shop_items"

    for path in sources:
        doc = read_doc(path)
        if doc is None:
            continue
        try:
            rel = path.relative_to(legacy_dir)
        except ValueError:
            rel = path.name
        name = "_".join(Path(rel).parts)
        base_name = name[:-5] if name.lower().endswith(".json") else name
        in_categories: dict[str, dict] = {}
        out_categories: dict[str, dict] = {}
        kept_in = kept_out = 0

        categories = doc.get("categories")
        if not isinstance(categories, list):
            print(f"  ! {path} 没有 categories 数组，已跳过")
            continue

        for category in categories:
            if not isinstance(category, dict):
                continue
            meta = copy_category_meta(category)
            cid = meta["id"]
            if not cid:
                print(f"  ! {path} 里有分类缺少 id，已跳过该分类")
                continue
            items = category.get("items")
            if not isinstance(items, list):
                items = []
            for index, item in enumerate(items):
                where = f"{path.name} / {cid} / 第 {index + 1} 条"
                if not isinstance(item, dict):
                    print(f"  ! {where} 不是对象，已跳过")
                    continue
                item_id = str(item.get("item") or "").strip()
                if not item_id:
                    print(f"  ! {where} 缺少 item，已跳过")
                    continue
                components = clean_components(item, where)
                price = as_int(item.get("price"), 0)
                sell_price = as_int(item.get("sell_price"), 0)
                stock = as_int(item.get("stock"), UNLIMITED)
                if stock < UNLIMITED:
                    stock = UNLIMITED

                if price >= 1:
                    entry: dict[str, Any] = {"item": item_id, "price": price, "stock": stock}
                    if components:
                        entry["components"] = components
                    in_categories.setdefault(cid, {**meta, "items": []})["items"].append(entry)
                    kept_in += 1

                if sell_price >= 1:
                    payout = sell_price
                    if price >= 1 and payout > price:
                        payout = price
                        notes.append(f"{item_id}：sell_price {sell_price} > price {price}，已压到 {price}")
                    entry = {"item": item_id, "sell_price": payout}
                    if components:
                        entry["components"] = components
                    out_categories.setdefault(cid, {**meta, "items": []})["items"].append(entry)
                    kept_out += 1

        def wrap(cats: dict[str, dict]) -> dict:
            return {"version": NEW_FORMAT_VERSION, "categories": [cats[k] for k in sorted(cats)]}

        if in_categories:
            in_files[f"{base_name}.json"] = wrap(in_categories)
        if out_categories:
            out_files[f"{base_name}.json"] = wrap(out_categories)
        print(f"  {path}：买入 {kept_in} 条 / 卖出 {kept_out} 条")

    return in_files, out_files, notes


def as_int(value: Any, default: int) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def write_files(target_dir: Path, files: dict[str, dict], apply: bool, overwrite: bool) -> int:
    written = 0
    for name in sorted(files):
        target = target_dir / name
        if target.exists() and not overwrite:
            print(f"  - 已存在，跳过（加 --overwrite 可覆盖）：{target}")
            continue
        if apply:
            target_dir.mkdir(parents=True, exist_ok=True)
            target.write_text(json.dumps(files[name], ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            written += 1
        print(f"  {'写入' if apply else '将写入'}：{target}")
    return written


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    config_dir: Path = args.config_dir
    sources = iter_sources(config_dir)
    print(f"配置目录：{config_dir.resolve()}")
    if not sources:
        print("没有找到旧的 shop_items 目录或 shop_items.json，无需迁移。")
        return 0
    print(f"发现 {len(sources)} 个旧配置来源：")
    for path in sources:
        print(f"  - {path}")

    in_files, out_files, notes = convert(sources, config_dir)
    if notes:
        print("\n提示：")
        for note in notes:
            print(f"  * {note}")
    if not in_files and not out_files:
        print("\n没有可迁移的条目（price / sell_price 都 <= 0）。")
        return 0

    print(f"\n买入配置 -> {config_dir / 'in'}")
    write_files(config_dir / "in", in_files, args.apply, args.overwrite)
    print(f"\n卖出配置 -> {config_dir / 'out'}")
    write_files(config_dir / "out", out_files, args.apply, args.overwrite)

    if args.apply:
        print("\n完成。旧文件未改动；重启服务器或执行 /bmcs reload 生效。")
    else:
        print("\n以上为预览。确认无误后加 --apply 真正写入。")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
