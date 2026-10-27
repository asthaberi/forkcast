"""Generates dashboards/forkcast.json. Edit the panel list here, then run: python monitoring/grafana/build_dashboard.py

Kept as code because a Grafana dashboard is ~1,000 lines of JSON in which the only interesting part is
the PromQL; this way each panel is one readable line.
"""
import json
from pathlib import Path

APP = 'application="forkcast"'
API = f'{APP}, uri=~"/api/.*"'
RATE = '$__rate_interval'

# (title, unit, [(PromQL, legend)], width)
ROWS = [
    ('Traffic and latency', [
        ('Requests per second by endpoint', 'reqps',
         [(f'sum by (method, uri) (rate(http_server_requests_seconds_count{{{API}}}[{RATE}]))', '{{method}} {{uri}}')], 12),
        ('p95 latency by endpoint', 's',
         [(f'histogram_quantile(0.95, sum by (le, method, uri) (rate(http_server_requests_seconds_bucket{{{API}}}[{RATE}])))',
           '{{method}} {{uri}}')], 12),
        ('Server errors (5xx) share', 'percentunit',
         # "or vector(0)": with no 5xx at all the series would be empty and show "No data" instead of 0%
         [(f'(sum(rate(http_server_requests_seconds_count{{{API}, status=~"5.."}}[{RATE}])) or vector(0)) / '
           f'sum(rate(http_server_requests_seconds_count{{{API}}}[{RATE}]))', '5xx share')], 8),
        ('Responses by status', 'reqps',
         [(f'sum by (status) (rate(http_server_requests_seconds_count{{{API}}}[{RATE}]))', '{{status}}')], 8),
        ('Checkout: new vs replayed orders', 'reqps',
         [(f'sum by (status) (rate(http_server_requests_seconds_count{{{APP}, method="POST", uri="/api/v1/orders"}}[{RATE}]))',
           '{{status}} (201 new, 200 replayed, 409 conflict)')], 8),
    ]),
    ('Cache, rate limiting and resilience', [
        ('Cache hit ratio', 'percentunit',
         [(f'sum by (name) (rate(cache_gets_total{{{APP}, result="hit"}}[{RATE}])) / '
           f'sum by (name) (rate(cache_gets_total{{{APP}, result=~"hit|miss"}}[{RATE}]))', '{{name}}')], 8),
        ('Rate limit decisions', 'reqps',
         [(f'sum by (policy, outcome) (rate(forkcast_rate_limit_decisions_total{{{APP}}}[{RATE}]))',
           '{{policy}} {{outcome}}')], 8),
        ('Redis circuit breakers (1 = open, Redis skipped)', 'short',
         [(f'max by (name) (forkcast_circuit_breaker_open{{{APP}}})', '{{name}} open'),
          (f'sum by (name) (increase(forkcast_circuit_breaker_failures_total{{{APP}}}[1m]))', '{{name}} failures/min')], 8),
    ]),
    ('Gemini (AI)', [
        ('Gemini calls per minute', 'short',
         [(f'sum by (gen_ai_operation_name, gen_ai_request_model, error) (increase(gen_ai_client_operation_seconds_count{{{APP}}}[1m]))',
           '{{gen_ai_operation_name}} {{gen_ai_request_model}} error={{error}}')], 8),
        ('Gemini p95 latency', 's',
         [(f'histogram_quantile(0.95, sum by (le, gen_ai_operation_name) (rate(gen_ai_client_operation_seconds_bucket{{{APP}}}[5m])))',
           '{{gen_ai_operation_name}}')], 8),
        ('Gemini tokens per hour', 'short',
         [(f'sum by (gen_ai_token_type) (increase(gen_ai_client_token_usage_total{{{APP}}}[1h]))', '{{gen_ai_token_type}}')], 8),
    ]),
    ('Data stores and JVM', [
        ('MongoDB p95 latency by command', 's',
         [(f'histogram_quantile(0.95, sum by (le, collection, command) (rate(mongodb_driver_commands_seconds_bucket{{{APP}}}[{RATE}])))',
           '{{command}} {{collection}}')], 6),
        ('Redis p95 latency by command', 's',
         [(f'histogram_quantile(0.95, sum by (le, db_operation) (rate(lettuce_seconds_bucket{{{APP}}}[{RATE}])))',
           '{{db_operation}}')], 6),
        ('MongoDB connections in use', 'short',
         [(f'sum(mongodb_driver_pool_checkedout{{{APP}}})', 'checked out'),
          (f'sum(mongodb_driver_pool_size{{{APP}}})', 'pool size')], 6),
        ('JVM heap and CPU', 'percentunit',
         [(f'sum(jvm_memory_used_bytes{{{APP}, area="heap"}}) / sum(jvm_memory_max_bytes{{{APP}, area="heap"}} > 0)', 'heap used'),
          (f'process_cpu_usage{{{APP}}}', 'process CPU'),
          (f'system_cpu_usage{{{APP}}}', 'machine CPU')], 6),
    ]),
]


def panel(panel_id, title, unit, queries, x, y, width):
    return {
        'id': panel_id,
        'type': 'timeseries',
        'title': title,
        'datasource': {'type': 'prometheus', 'uid': 'prometheus'},
        'gridPos': {'x': x, 'y': y, 'w': width, 'h': 8},
        'fieldConfig': {
            'defaults': {'unit': unit, 'min': 0, **({'max': 1} if unit == 'percentunit' else {}),
                         'custom': {'lineWidth': 1, 'fillOpacity': 10, 'spanNulls': True}},
            'overrides': [],
        },
        'options': {'legend': {'displayMode': 'list', 'placement': 'bottom'}, 'tooltip': {'mode': 'multi'}},
        'targets': [
            {'refId': chr(ord('A') + i), 'expr': expr, 'legendFormat': legend,
             'datasource': {'type': 'prometheus', 'uid': 'prometheus'}}
            for i, (expr, legend) in enumerate(queries)
        ],
    }


def build():
    panels, panel_id, y = [], 1, 0
    for row_title, row_panels in ROWS:
        panels.append({'id': panel_id, 'type': 'row', 'title': row_title, 'collapsed': False,
                       'gridPos': {'x': 0, 'y': y, 'w': 24, 'h': 1}, 'panels': []})
        panel_id, y, x = panel_id + 1, y + 1, 0
        for title, unit, queries, width in row_panels:
            if x + width > 24:
                x, y = 0, y + 8
            panels.append(panel(panel_id, title, unit, queries, x, y, width))
            panel_id, x = panel_id + 1, x + width
        y += 8
    return {
        'uid': 'forkcast',
        'title': 'Forkcast',
        'tags': ['forkcast'],
        'timezone': 'browser',
        'refresh': '5s',
        'time': {'from': 'now-15m', 'to': 'now'},
        'schemaVersion': 39,
        'panels': panels,
    }


if __name__ == '__main__':
    out = Path(__file__).parent / 'dashboards' / 'forkcast.json'
    out.parent.mkdir(exist_ok=True)
    out.write_text(json.dumps(build(), indent=2) + '\n', encoding='utf-8')
    print(f'wrote {out}')
