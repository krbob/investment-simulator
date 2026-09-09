#!/usr/bin/env python3
"""Render reproducible research figures from the JVM-generated CSVs (requires Matplotlib)."""

import csv
from pathlib import Path
import sys

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.colors import TwoSlopeNorm
import numpy as np


def plot(directory, name, x_key, y_key, x_label, y_label, title, y_percent=False):
    with (directory / (name + ".csv")).open(newline="") as stream:
        rows = list(csv.DictReader(stream))
    xs = sorted({float(row[x_key]) for row in rows})
    ys = sorted({float(row[y_key]) for row in rows})
    values = np.full((len(ys), len(xs)), np.nan)
    infeasible = np.zeros(values.shape, dtype=bool)
    for row in rows:
        i, j = ys.index(float(row[y_key])), xs.index(float(row[x_key]))
        infeasible[i, j] = any(float(row[key]) > 0 for key in (
            "baseline_shortfall_pln", "oki_shortfall_pln", "baseline_unpaid_tax_pln", "oki_unpaid_tax_pln"
        ))
        infeasible[i, j] |= min(float(row["baseline_real_net_pln"]), float(row["oki_real_net_pln"])) < 0
        if not infeasible[i, j]:
            values[i, j] = float(row["advantage_real_pln"]) / 1000
    maximum = max(float(np.nanmax(np.abs(values))) if np.any(np.isfinite(values)) else 1, 0.1)
    fig, ax = plt.subplots(figsize=(10, 6), layout="constrained")
    color_map = plt.get_cmap("RdBu").copy()
    color_map.set_bad("#dedede")
    color = ax.imshow(values, origin="lower", cmap=color_map,
                      norm=TwoSlopeNorm(vmin=-maximum, vcenter=0, vmax=maximum), aspect="auto")
    ax.set_xticks(range(len(xs)), [f"{value:g}" for value in xs])
    ax.set_yticks(range(len(ys)), [f"{value * 100:g}%" if y_percent else f"{value:g}" for value in ys])
    ax.set_xlabel(x_label)
    ax.set_ylabel(y_label)
    ax.set_title(title, loc="left", weight="bold", pad=16)
    for i in range(len(ys)):
        for j in range(len(xs)):
            if infeasible[i, j]:
                label, text_color = "X", "#555555"
            else:
                label = f"{values[i, j]:+.1f}"
                text_color = "white" if abs(values[i, j]) > 0.58 * maximum else "#222222"
            ax.text(j, i, label, ha="center", va="center", color=text_color, fontsize=9)
    fig.colorbar(color, ax=ax, label="Migration advantage (thousand start-date PLN)")
    fig.supxlabel("Illustrative deterministic paths: 7% return, 2.5% CPI, no OKI allowance.\n"
                  "Positive = migration ahead; X = unmet spending or insolvency in either strategy.", fontsize=9)
    fig.savefig(directory / (name + ".png"), dpi=180)
    fig.savefig(directory / (name + ".svg"))
    plt.close(fig)


def main():
    directory = Path(sys.argv[1] if len(sys.argv) == 2 else "docs/research")
    plot(directory, "horizon-rate", "horizon_years", "oki_rate", "Horizon (years)", "Constant OKI rate",
         "Taxable brokerage → OKI: horizon and asset tax", y_percent=True)
    plot(directory, "horizon-gain", "horizon_years", "unrealized_gain_fraction", "Horizon (years)",
         "Unrealized gain / initial portfolio value", "Cost of realizing existing gains on migration", y_percent=True)
    plot(directory, "withdrawal-start-amount", "withdrawal_start_after_years", "monthly_withdrawal_pln",
         "Years until withdrawals start", "Monthly net spending (start-date PLN)",
         "30-year plan: timing and size of withdrawals")
    print("Wrote three PNG/SVG figures to", directory)


if __name__ == "__main__":
    main()
