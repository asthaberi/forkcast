"""
Load test for Forkcast's read endpoints, used to measure the effect of the Redis cache.

Simulates users browsing: each session searches for nearby restaurants and often opens a menu.
User locations cluster around neighbourhood hotspots (people live and work in the same places),
with some spread out at random. The same seed always generates the same traffic, so runs with and
without the cache see identical requests.

Reports latency per endpoint and the number of MongoDB commands the app ran, read from Spring Boot
Actuator before and after the run. Actuator metrics need an admin token, so the script logs in as the
seeded local-development admin unless other credentials are passed.

Start the app with RATE_LIMIT_ENABLED=false first: the run sends thousands of requests from one IP, which
the API-wide rate limit would otherwise answer with 429.

Usage:
    python benchmark/cache_benchmark.py --sessions 1500 --concurrency 20 --label redis
"""

import argparse
import json
import math
import random
import statistics
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor

CENTER = (26.2124, 78.1772)  # Gwalior city centre, where the sample data lives
CUISINES = ["biryani", "north indian", "pizza", "south indian", "chinese", "desserts"]
HOTSPOTS = 40
HOTSPOT_SHARE = 0.7        # 70% of users are near a hotspot, 30% anywhere in the area
HOTSPOT_SPREAD_M = 100     # standard deviation around a hotspot
AREA_RADIUS_KM = 3.0
MENU_OPEN_CHANCE = 0.6


def get_json(url, token=None):
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=10) as response:
        return json.load(response)


def admin_token(base_url, email, password):
    req = urllib.request.Request(f"{base_url}/api/v1/auth/login", method="POST",
                                 data=json.dumps({"email": email, "password": password}).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=10) as response:
        return json.load(response)["accessToken"]


def timed_get(url):
    start = time.perf_counter()
    with urllib.request.urlopen(url, timeout=10) as response:
        body = response.read()
        status = response.status
    return (time.perf_counter() - start) * 1000, status, body


def offset(lat, lng, north_m, east_m):
    d_lat = north_m / 111_320
    d_lng = east_m / (111_320 * math.cos(math.radians(lat)))
    return lat + d_lat, lng + d_lng


def random_point_in_area(rng):
    r = AREA_RADIUS_KM * 1000 * math.sqrt(rng.random())
    theta = rng.uniform(0, 2 * math.pi)
    return offset(*CENTER, r * math.cos(theta), r * math.sin(theta))


def build_sessions(count, seed):
    rng = random.Random(seed)
    hotspots = [random_point_in_area(rng) for _ in range(HOTSPOTS)]
    sessions = []
    for _ in range(count):
        if rng.random() < HOTSPOT_SHARE:
            base = rng.choice(hotspots)
            lat, lng = offset(*base, rng.gauss(0, HOTSPOT_SPREAD_M), rng.gauss(0, HOTSPOT_SPREAD_M))
        else:
            lat, lng = random_point_in_area(rng)
        roll = rng.random()
        params = f"latitude={lat:.6f}&longitude={lng:.6f}"
        if roll < 0.2:
            params += "&cuisine=" + urllib.request.quote(rng.choice(CUISINES))
        elif roll < 0.3:
            params += "&openNow=true"
        menu_pick = rng.randrange(5) if rng.random() < MENU_OPEN_CHANCE else None
        sessions.append((params, menu_pick))
    return sessions


def run_session(base_url, session):
    params, menu_pick = session
    results = []
    ms, status, body = timed_get(f"{base_url}/api/v1/restaurants?{params}")
    results.append(("search", ms, status))
    restaurants = json.loads(body)["restaurants"]
    if menu_pick is not None and restaurants:
        restaurant = restaurants[min(menu_pick, len(restaurants) - 1)]
        ms, status, _ = timed_get(f"{base_url}/api/v1/restaurants/{restaurant['id']}/menu")
        results.append(("menu", ms, status))
    return results


def mongo_command_count(base_url, token):
    data = get_json(f"{base_url}/actuator/metrics/mongodb.driver.commands", token)
    return next(m["value"] for m in data["measurements"] if m["statistic"] == "COUNT")


def cache_gets(base_url, result, token):
    try:
        data = get_json(f"{base_url}/actuator/metrics/cache.gets?tag=result:{result}", token)
        return data["measurements"][0]["value"]
    except Exception:
        return 0.0  # metric absent when caching is disabled


def percentile(values, p):
    ordered = sorted(values)
    index = max(0, math.ceil(p / 100 * len(ordered)) - 1)
    return ordered[index]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--sessions", type=int, default=1500)
    parser.add_argument("--concurrency", type=int, default=20)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--label", default="run")
    parser.add_argument("--json-out")
    parser.add_argument("--admin-email", default="admin@forkcast.example")
    parser.add_argument("--admin-password", default="admin-dev-password")
    args = parser.parse_args()
    token = admin_token(args.base_url, args.admin_email, args.admin_password)

    sessions = build_sessions(args.sessions, args.seed)
    mongo_before = mongo_command_count(args.base_url, token)
    hits_before, misses_before = cache_gets(args.base_url, "hit", token), cache_gets(args.base_url, "miss", token)

    start = time.perf_counter()
    with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        all_results = [r for rs in pool.map(lambda s: run_session(args.base_url, s), sessions) for r in rs]
    elapsed = time.perf_counter() - start

    mongo_commands = mongo_command_count(args.base_url, token) - mongo_before
    hits = cache_gets(args.base_url, "hit", token) - hits_before
    misses = cache_gets(args.base_url, "miss", token) - misses_before

    report = {"label": args.label, "sessions": args.sessions, "requests": len(all_results),
              "errors": sum(1 for _, _, status in all_results if status != 200),
              "elapsed_s": round(elapsed, 2), "throughput_rps": round(len(all_results) / elapsed, 1),
              "mongo_commands": int(mongo_commands),
              "mongo_commands_per_request": round(mongo_commands / len(all_results), 3),
              "cache_hit_ratio": round(hits / (hits + misses), 3) if hits + misses else None,
              "latency_ms": {}}
    for endpoint in ("search", "menu"):
        times = [ms for name, ms, _ in all_results if name == endpoint]
        report["latency_ms"][endpoint] = {
            "count": len(times), "p50": round(statistics.median(times), 2),
            "p95": round(percentile(times, 95), 2), "p99": round(percentile(times, 99), 2)}

    print(json.dumps(report, indent=2))
    if args.json_out:
        with open(args.json_out, "w") as f:
            json.dump(report, f, indent=2)


if __name__ == "__main__":
    main()
