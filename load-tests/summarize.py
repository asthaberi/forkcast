"""Turns the k6 JSON summaries in load-tests/results into one Markdown table.

    python load-tests/summarize.py
"""
import json
import re
from pathlib import Path

RESULTS = Path(__file__).parent / 'results'
DURATION_S = 30  # matches DURATION in run.sh
# The request each scenario is about; other requests (setup, adding to the cart) are excluded from latency
KEY_REQUEST = {
    'browse': 'http_req_duration',
    'checkout': 'http_req_duration{name:place-order}',
    'recommendations': 'http_req_duration{name:recommendations}',
}


def row(path):
    scenario, rate = re.match(r'(\w+)-(\d+)rps', path.stem).groups()
    metrics = json.loads(path.read_text())['metrics']
    latency = metrics[KEY_REQUEST[scenario]]['values']
    iterations = metrics['iterations']['values']['count']
    dropped = metrics.get('dropped_iterations', {}).get('values', {}).get('count', 0)
    failed = metrics['http_req_failed']['values']['rate']
    # Per scheduled second: a few hung connections can stretch the wall-clock run (k6 waits up to 30 s for
    # them), which would understate the rate the test actually sustained
    return (scenario, int(rate), iterations / DURATION_S, latency['med'], latency['p(95)'], latency['p(99)'],
            failed * 100, dropped)


def main():
    rows = sorted((row(p) for p in RESULTS.glob('*rps.json')), key=lambda r: (r[0], r[1]))
    print('| Scenario | Target/s | Achieved/s | p50 ms | p95 ms | p99 ms | Errors | Dropped |')
    print('|---|---|---|---|---|---|---|---|')
    for scenario, target, achieved, p50, p95, p99, errors, dropped in rows:
        print(f'| {scenario} | {target} | {achieved:.0f} | {p50:.1f} | {p95:.1f} | {p99:.1f} | {errors:.2f}% | {dropped:.0f} |')


if __name__ == '__main__':
    main()
