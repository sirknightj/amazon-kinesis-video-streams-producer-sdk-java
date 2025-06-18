import argparse
import glob
import logging
import os
from typing import List, Tuple, Optional

import matplotlib.pyplot as plt
from matplotlib.colors import TABLEAU_COLORS
import numpy as np
import pandas as pd

green_color = TABLEAU_COLORS['tab:green']

logger = logging.getLogger(__name__)


def convert_to_bytes(value: float, unit: str) -> float:
    """
    Convert storage units to bytes.

    Args:
        value: The numeric value to convert
        unit: The unit to convert from (KB, MB, KiB, MiB, GB, GiB)

    Returns:
        Value in bytes
    """
    # Define conversion factors
    conversion = {
        'KB': 1000,
        'MB': 1000 * 1000,
        'GB': 1000 * 1000 * 1000,
        'KiB': 1024,
        'MiB': 1024 * 1024,
        'GiB': 1024 * 1024 * 1024
    }

    return value * conversion.get(unit, 1)


def get_column_reference(df: pd.DataFrame, column_spec: str) -> str:
    """
    Convert a column specification (name or index) to a column name.

    Args:
        df: DataFrame containing the data
        column_spec: Column specification (name or index)

    Returns:
        Column name
    """
    try:
        # Try to convert to integer for index-based access
        idx = int(column_spec)
        if idx < 0 or idx >= len(df.columns):
            raise ValueError(f"Column index {idx} is out of range [0, {len(df.columns) - 1}]")
        return df.columns[idx]
    except ValueError:
        # If conversion fails, treat as column name
        if column_spec not in df.columns:
            raise ValueError(f"Column '{column_spec}' not found in CSV file. Available columns: {list(df.columns)}")
        return column_spec


def parse_csv_columns(file_path: str, x_column: str, y_column: str,
                      zero_start: bool = False, zero_end: bool = False,
                      convert_units: bool = False) -> Tuple[str, str, np.array, np.array, Optional[pd.Timestamp]]:
    """
    Parse specified columns from a CSV file.
    If x_column contains timestamps, converts them to duration from first timestamp.

    Args:
        file_path: Path to the CSV file
        x_column: Name of the column to use for x-axis
        y_column: Name of the column to use for y-axis
        zero_start: Whether to add a zero value at the start
        zero_end: Whether to add a zero value at the end
        convert_units: Whether to convert the units in the y-axis to bytes

    Returns:
        X and Y column names
        Tuple of (x_values, y_values)
        Timestamp of the first data point
    """
    if not os.path.exists(file_path):
        raise FileNotFoundError(f"File not found: {file_path}")

    df = pd.read_csv(file_path)
    start_time = None

    # Check for storage units in column names and convert values
    storage_units = ['KB', 'MB', 'KiB', 'MiB', 'GB', 'GiB']

    # Convert column specifications to actual column names
    x_column = get_column_reference(df, x_column)
    y_column = get_column_reference(df, y_column)

    # Try to convert x_column to datetime if it contains timestamp data
    # Note: We want to have durations instead of dates (e.g. 1 second in, 10 minutes in, etc...)
    try:
        timestamps = pd.to_datetime(df[x_column])
        # Convert to duration in seconds from first timestamp
        start_time = timestamps.iloc[0]
        x_values = [(t - start_time).total_seconds() for t in timestamps]
        x_values = np.array(x_values)

    except (ValueError, TypeError):
        # If conversion fails, use original values
        x_values = np.array(df[x_column])

    # Convert y values if needed
    y_unit = next((unit for unit in storage_units if unit in y_column), None)
    if y_unit and convert_units:
        df[y_column] = df[y_column].apply(lambda x: convert_to_bytes(x, y_unit))
    y_values = np.array(df[y_column])

    # Calculate typical interval (use the first interval as reference)
    if len(x_values) >= 2:
        interval = x_values[1] - x_values[0]
    else:
        interval = 1.0  # fallback if only one point

    if zero_start:
        x_values = np.concatenate((x_values, [x_values[-1] + interval]))
        y_values = np.concatenate(([0], y_values))

    if zero_end:
        x_values = np.concatenate((x_values, [x_values[-1] + interval]))
        y_values = np.concatenate((y_values, [0]))

    return x_column, y_column, x_values, y_values, start_time


def convert_memory_units(memory_values: np.ndarray) -> Tuple[np.ndarray, str]:
    """
    Convert memory values to appropriate units (Bytes, KiB, MiB, or GiB).

    Args:
        memory_values: Array of memory values in bytes

    Returns:
        Tuple of (converted values, unit string)
    """
    max_value = np.max(memory_values)

    if max_value > 1024 * 1024 * 1024:  # More than 1 GiB
        return memory_values / (1024 * 1024 * 1024), 'GiB'
    elif max_value > 1024 * 1024:  # More than 1 MiB
        return memory_values / (1024 * 1024), 'MiB'
    elif max_value > 1024:  # More than 1 KiB
        return memory_values / 1024, 'KiB'
    else:
        return memory_values, 'Bytes'


def plot_data(datasets: List[Tuple[str, np.array, np.array]],
              x_label: str,
              y_label: str,
              title: str,
              save_path: Optional[str] = None,
              key_points: Optional[List[Tuple[float, str]]] = None,
              y_min: Optional[float] = None,
              y_max: Optional[float] = None,
              convert_memory: bool = False) -> None:
    """
    Plot data from CSV columns.

    Args:
        datasets: List of tuples containing (x_values, y_values) for each dataset
        x_label: Label for x-axis
        y_label: Label for y-axis
        title: Plot title
        save_path: Path to save the plot
        key_points: List of tuples containing (x_value, label) for marking points
        y_min: Minimum value for y-axis
        y_max: Maximum value for y-axis
        convert_memory: Whether to convert y-values to appropriate memory units
    """
    fig, ax = plt.subplots(figsize=(14, 6))

    # Convert memory values if requested
    conversion_factor = 1
    if convert_memory:
        # Find max value across all datasets
        unit = 'Bytes'
        max_value = max(np.max(y_vals) for _, _, y_vals in datasets)

        if max_value > 1024 * 1024 * 1024:
            conversion_factor = 1024 * 1024 * 1024
            unit = 'GiB'
        elif max_value > 1024 * 1024:
            conversion_factor = 1024 * 1024
            unit = 'MiB'
        elif max_value > 1024:
            conversion_factor = 1024
            unit = 'KiB'

        y_label = f"{y_label} ({unit})"

    colors = list(TABLEAU_COLORS.values())
    for i, (file_name, x_values, y_values) in enumerate(datasets):
        if convert_memory:
            y_values = y_values / conversion_factor
        ax.plot(x_values, y_values, color=colors[i % len(colors)],
                label=os.path.basename(file_name), alpha=1 if len(datasets) == 1 else 0.7)

    # Add legend outside the plot
    if len(datasets) > 1:
        # Adjust the plot layout to make room for the legend
        box = ax.get_position()
        ax.set_position([box.x0, box.y0, box.width * 0.9, box.height])

        # Place legend to the right of the plot
        ax.legend(loc='center left', bbox_to_anchor=(1, 0.5))

    ax.set_xlabel(x_label, fontsize='large')
    ax.set_ylabel(y_label, fontsize='large')
    plt.title(title.replace('\\n', '\n'), fontsize='x-large')

    ax.grid(True)

    # Set y-axis limits if provided
    if y_min is not None:
        ax.set_ylim(bottom=y_min)
    if y_max is not None:
        ax.set_ylim(top=y_max)

    ax.set_xlim(left=0)

    # Format x-axis labels as HH:MM:SS
    def format_time(x, _):
        hours = int(x // 3600)
        minutes = int((x % 3600) // 60)
        seconds = int(x % 60)
        return f"{hours:02d}:{minutes:02d}:{seconds:02d}"

    ax.xaxis.set_major_formatter(plt.FuncFormatter(format_time))

    if key_points:
        # Get the current y-axis limits
        y_min_plot, y_max_plot = ax.get_ylim()
        usable_range = y_max_plot - y_min_plot

        # Find global x range across all datasets
        x_min = min(np.min(x_vals) for _, x_vals, _ in datasets)
        x_max = max(np.max(x_vals) for _, x_vals, _ in datasets)

        # Initialize storage for used label positions
        ax._used_label_positions = []

        for x_val, label in key_points:
            ax.axvline(x=x_val, color=green_color, linestyle=':')

            # Find all y-values near this x-value across all datasets
            window = (x_max - x_min) * 0.02  # 2% of total range
            window_values = []
            for _, x_values, y_values in datasets:
                window_indices = np.where(np.abs(x_values - x_val) <= window)[0]
                window_values.extend(y_values[window_indices])

            # Define possible positions from center outward
            positions = [
                (y_min_plot + 0.5 * usable_range, 'center'),
                (y_min_plot + 0.3 * usable_range, 'bottom'),
                (y_max_plot - 0.3 * usable_range, 'top'),
                (y_min_plot + 0.15 * usable_range, 'bottom'),
                (y_max_plot - 0.15 * usable_range, 'top')
            ]

            # Find best position for label
            best_position = None
            max_min_distance = -float('inf')

            for pos, alignment in positions:
                # Check distance from data points
                distances = np.abs(np.array(window_values) - pos)
                min_distance_to_data = np.min(distances) if len(distances) > 0 else float('inf')

                # Check distance from other labels
                min_distance_to_labels = float('inf')
                for used_pos in ax._used_label_positions:
                    label_distance = abs(pos - used_pos)
                    min_distance_to_labels = min(min_distance_to_labels, label_distance)

                # Combine both metrics with a preference for center position
                if alignment == 'center':
                    position_score = min_distance_to_data * 1.5  # Prefer center position
                else:
                    position_score = min_distance_to_data

                # Add penalty for being close to other labels
                if min_distance_to_labels < usable_range * 0.1:  # 10% of range
                    position_score *= 0.5

                if position_score > max_min_distance:
                    max_min_distance = position_score
                    best_position = (pos, alignment)

            label_y, vertical_alignment = best_position
            ax._used_label_positions.append(label_y)

            # Sort used positions to maintain consistency
            ax._used_label_positions.sort()

            ax.text(x_val, label_y, label,
                    rotation=90,
                    verticalalignment=vertical_alignment,
                    horizontalalignment='center',
                    bbox={"facecolor": 'white',
                          "alpha": 0.8,
                          "edgecolor": 'none',
                          "pad": 2})

    plt.xticks(rotation=45)

    if save_path:
        plt.savefig(save_path, bbox_inches='tight')
        logger.info(f"Plot saved to {save_path}")
    else:
        plt.show()


def main():
    logging.basicConfig(
        level=logging.INFO,
        format='%(asctime)s [%(filename)s:%(lineno)s/%(funcName)-s()] [%(levelname)s] %(message)s',
        handlers=[
            logging.StreamHandler()  # Outputs logs to the console
        ]
    )

    parser = argparse.ArgumentParser(description='Plot CSV data columns')

    # Required arguments
    parser.add_argument('data_files', nargs='+',
                        help='Input CSV file(s)')
    parser.add_argument('--x-column', required=True,
                        help='Column name for x-axis')
    parser.add_argument('--y-column', required=True,
                        help='Column name for y-axis')

    # Optional arguments
    parser.add_argument('--output', '-o',
                        help='Path to save the output plot')
    parser.add_argument('--title', '-t', default='Data Plot',
                        help='Title for the graph')
    parser.add_argument('--x-label',
                        help='Label for x-axis (index starting from 0, or the exact column name)')
    parser.add_argument('--y-label',
                        help='Label for y-axis (index starting from 0, or the exact column name)')
    parser.add_argument('--key-points', '-k', nargs=2, action='append',
                        metavar=('VALUE', 'LABEL'),
                        help='Key points to mark with vertical labels')
    parser.add_argument('--y-min', type=float,
                        help='Minimum value for y-axis')
    parser.add_argument('--y-max', type=float,
                        help='Maximum value for y-axis')
    parser.add_argument('--convert-memory', action='store_true',
                        help='Convert y-axis values to appropriate memory units')
    parser.add_argument('--zero-start', action='store_true',
                       help='Add a zero value data point at the start')
    parser.add_argument('--zero-end', action='store_true',
                       help='Add a zero value data point at the end')

    args = parser.parse_args()

    key_points = []
    if args.key_points:
        for value, label in args.key_points:
            key_points.append((float(value), label))

    # File name, x_values, y_values
    datasets: List[Tuple[str, np.ndarray, np.ndarray]] = []
    start_times = []

    # Expand paths, handle globs, and flatten the list
    data_files = []
    for data_file in args.data_files:
        for file in data_file.split():
            if file.strip():
                expanded_path = os.path.expanduser(file)
                # If the path contains a wildcard, use glob
                if '*' in expanded_path:
                    glob_matches = glob.glob(expanded_path)
                    # Sort the glob matches to ensure consistent ordering
                    data_files.extend(sorted(glob_matches))
                else:
                    data_files.append(expanded_path)

    if not data_files:
        raise ValueError("No input files found! Please check your file paths and patterns.")

    logger.info(f"Processing {len(data_files)} files: {[os.path.basename(f) for f in data_files]}")

    x_col = args.x_column
    y_col = args.y_column

    # First pass: collect all data and start times
    for data_file in data_files:
        x_col, y_col, x_values, y_values, start_time = parse_csv_columns(
            data_file,
            args.x_column,
            args.y_column,
            zero_start=args.zero_start,
            zero_end=args.zero_end,
            convert_units=args.convert_memory,
        )
        datasets.append((data_file, x_values, y_values))
        if start_time is not None:
            start_times.append(start_time)

    # If we have timestamp data, adjust all x_values relative to the earliest start time
    if start_times:
        earliest_start = min(start_times)
        adjusted_datasets = []

        for i, (file_name, x_values, y_values) in enumerate(datasets):
            if i < len(start_times):  # This dataset has timestamp data
                offset = (start_times[i] - earliest_start).total_seconds()
                adjusted_x = x_values + offset
                adjusted_datasets.append((file_name, adjusted_x, y_values))
            else:
                adjusted_datasets.append((file_name, x_values, y_values))

        datasets = adjusted_datasets

    x_label = args.x_label if args.x_label else x_col
    y_label = args.y_label if args.y_label else y_col

    plot_data(
        datasets=datasets,
        x_label=x_label,
        y_label=y_label,
        title=args.title,
        save_path=args.output,
        key_points=key_points,
        y_min=args.y_min,
        y_max=args.y_max,
        convert_memory=args.convert_memory
    )


if __name__ == "__main__":
    main()
