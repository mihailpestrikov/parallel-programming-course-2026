import csv
from pathlib import Path

import matplotlib.pyplot as plt
from matplotlib.ticker import FixedLocator, FuncFormatter, NullLocator

ROOT = Path(__file__).parent
SURFACE = "#fcfcfb"
TEXT = "#0b0b0b"
TEXT_SECONDARY = "#52514e"
GRID = "#e4e3df"

SERIES = [
    ("threadlocal", "Этап 3: Thread-Local", "#2a78d6"),
    ("double", "Этап 4: двойная буферизация", "#eb6834"),
    ("sync", "Этап 1: общий лок", "#1baf7a"),
    ("empty", "Этап 1: пустой лок", "#eda100"),
    ("striped", "Этап 2: шардированный лок", "#e87ba4"),
]

data = {}
with open(ROOT / "results" / "bench.csv", encoding="utf-8") as f:
    for row in csv.DictReader(f):
        data.setdefault(row["impl"], []).append((int(row["threads"]), float(row["median"])))

fig, ax = plt.subplots(figsize=(9, 5.5), dpi=150)
fig.patch.set_facecolor(SURFACE)
ax.set_facecolor(SURFACE)

for impl, label, color in SERIES:
    points = sorted(data[impl])
    xs = [t for t, _ in points]
    ys = [v for _, v in points]
    ax.plot(xs, ys, color=color, linewidth=2, marker="o", markersize=6,
            markeredgecolor=SURFACE, markeredgewidth=1.5, label=label, zorder=3)
    if impl == "empty":
        continue
    ax.annotate(f"{ys[-1]:.0f}", (xs[-1], ys[-1]), xytext=(8, 0), textcoords="offset points",
                va="center", fontsize=9, color=TEXT_SECONDARY)

baseline = data["single"][0][1]
ax.axhline(baseline, color=TEXT_SECONDARY, linewidth=1, linestyle="--", zorder=2)
ax.annotate(f"Этап 0: однопоточный baseline, {baseline:.0f}", (12, baseline), xytext=(0, -6),
            textcoords="offset points", ha="right", va="top", fontsize=9, color=TEXT_SECONDARY)

threads = sorted({t for points in data.values() for t, _ in points})
ax.set_xscale("log", base=2)
ax.xaxis.set_major_locator(FixedLocator(threads))
ax.xaxis.set_minor_locator(NullLocator())
ax.xaxis.set_major_formatter(FuncFormatter(lambda x, _: f"{x:.0f}"))
ax.set_xlim(0.9, 16)

ax.set_yscale("log")
ax.yaxis.set_major_formatter(FuncFormatter(lambda y, _: f"{y:g}"))
ax.yaxis.set_minor_formatter(FuncFormatter(lambda y, _: ""))

ax.set_xlabel("Число потоков T", color=TEXT_SECONDARY)
ax.set_ylabel("млн вызовов record() в секунду (лог. шкала)", color=TEXT_SECONDARY)
ax.set_title("Пропускная способность коллекторов метрик, Apple M3 Pro, Java 21",
             color=TEXT, loc="left", fontsize=12)

ax.grid(True, which="major", color=GRID, linewidth=0.8, zorder=0)
for side in ("top", "right"):
    ax.spines[side].set_visible(False)
for side in ("left", "bottom"):
    ax.spines[side].set_color(GRID)
ax.tick_params(colors=TEXT_SECONDARY, which="both")

legend = ax.legend(loc="center left", bbox_to_anchor=(1.02, 0.5), frameon=False, fontsize=9)
for text in legend.get_texts():
    text.set_color(TEXT)

fig.tight_layout()
fig.savefig(ROOT / "results" / "throughput.png", facecolor=SURFACE)
print("results/throughput.png")
