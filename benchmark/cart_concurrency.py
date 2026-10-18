"""
Concurrency check for the cart: many requests add the same item to one user's cart at the same moment.

Each round uses a brand-new user, so the requests also race to create the cart. A barrier releases all
threads together to maximise collisions. The cart is consistent if its final quantity equals the number
of adds that returned 200, i.e. no successful update was lost.

Usage:
    python benchmark/cart_concurrency.py --threads 20 --rounds 5
"""

import argparse
import json
import threading
import time
import urllib.error
import urllib.request
from collections import Counter


def request(base_url, method, path, user, body=None):
    req = urllib.request.Request(base_url + path, method=method,
                                 data=None if body is None else json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json", "X-User-Id": user})
    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read())


def pick_menu_item(base_url):
    with urllib.request.urlopen(f"{base_url}/api/v1/restaurants?latitude=26.2124&longitude=78.1772") as r:
        restaurant = json.load(r)["restaurants"][0]
    with urllib.request.urlopen(f"{base_url}/api/v1/restaurants/{restaurant['id']}/menu") as r:
        menu = json.load(r)
    return next(item for category in menu["categories"] for item in category["items"] if item["available"])


def run_round(base_url, item_id, threads, user):
    barrier = threading.Barrier(threads)
    statuses = []
    lock = threading.Lock()

    def add_one():
        barrier.wait()
        status, body = request(base_url, "POST", "/api/v1/cart/items", user, {"menuItemId": item_id, "quantity": 1})
        with lock:
            statuses.append(status if status == 200 else f"{status} {body.get('code', '')}".strip())

    workers = [threading.Thread(target=add_one) for _ in range(threads)]
    start = time.perf_counter()
    for worker in workers:
        worker.start()
    for worker in workers:
        worker.join()
    elapsed_ms = (time.perf_counter() - start) * 1000

    _, cart = request(base_url, "GET", "/api/v1/cart", user)
    final_quantity = sum(line["quantity"] for line in cart["items"])
    counts = Counter(statuses)
    return {"user": user, "succeeded": counts.pop(200, 0), "failed": dict(counts),
            "final_quantity": final_quantity, "elapsed_ms": round(elapsed_ms)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--threads", type=int, default=20)
    parser.add_argument("--rounds", type=int, default=5)
    args = parser.parse_args()

    item = pick_menu_item(args.base_url)
    print(f"Adding '{item['name']}' x1 from {args.threads} concurrent requests per round\n")
    lost_total = 0
    for round_number in range(1, args.rounds + 1):
        result = run_round(args.base_url, item["id"], args.threads, f"concurrency-{time.time_ns()}")
        lost = result["succeeded"] - result["final_quantity"]
        lost_total += max(lost, 0)
        verdict = "OK" if lost == 0 else f"LOST {lost} UPDATES"
        print(f"round {round_number}: {result['succeeded']:>2} succeeded, failed={result['failed'] or 0}, "
              f"final quantity={result['final_quantity']:>2}, {result['elapsed_ms']} ms -> {verdict}")
    print(f"\nlost updates across all rounds: {lost_total}")


if __name__ == "__main__":
    main()
