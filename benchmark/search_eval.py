"""
Retrieval evaluation for semantic dish search.

20 natural-language queries, each with hand-labelled relevant dishes from the seeded menus. Most are phrased
the way people talk ("something to cool me down on a hot day") rather than with words from the menu, which
is exactly where keyword search struggles.

Compares:
  - semantic: GET /api/v1/search/dishes (Gemini embeddings + Redis vector search)
  - keyword:  a BM25 baseline over the same dish text, computed here from the public menus

Metrics (averaged over queries):
  Hit@1, Hit@3  share of queries with a relevant dish at rank 1 / in the top 3
  Recall@5      relevant dishes found in the top 5, out of min(#relevant, 5)
  MRR@10        mean reciprocal rank of the first relevant dish in the top 10

--sweep also picks the minimum match score: it fetches scores for the 20 queries plus off-topic queries
that should return nothing, and reports, per threshold, how many relevant matches survive and how many
off-topic queries are correctly answered with no results.

Usage (app running with a Gemini key, ideally RATE_LIMIT_ENABLED=false and a low min-similarity for --sweep):
    python benchmark/search_eval.py [--base-url http://localhost:8080] [--json-out results.json] [--sweep]
"""

import argparse
import json
import math
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter

QUERIES = {
    "something to cool me down on a hot day": ["Kesar Lassi", "Masala Chaas", "Chocolate Shake", "Rasmalai"],
    "light healthy breakfast": ["Idli Sambar", "Berry Smoothie Bowl", "Avocado Toast", "Quinoa Buddha Bowl"],
    "really fiery dish for someone who loves heat": [
        "Mysore Masala Dosa", "Laal Maas", "Chilli Chicken", "Chicken 65", "Mutton Rogan Josh", "Fish Fry",
        "Kadai Paneer", "Mirchi Ka Salan", "Penne Arrabbiata", "Goan Fish Curry", "Chole Bhature"],
    "plant-based meal high in protein": ["Quinoa Buddha Bowl", "Tofu Stir Fry Bowl", "Tandoori Soya Chaap"],
    "fish or prawns in a coconut curry": ["Goan Fish Curry", "Prawn Gassi"],
    "late night craving": ["Hyderabadi Chicken Dum Biryani", "Mutton Biryani"],
    "Italian comfort food": ["Margherita Pizza", "Pepperoni Pizza", "Farmhouse Pizza", "Penne Arrabbiata", "Tiramisu"],
    "snacks you eat standing at a street stall": ["Pani Puri", "Aloo Tikki Chaat", "Raj Kachori", "Samosa"],
    "dessert flavoured with saffron": ["Double Ka Meetha", "Rasmalai"],
    "warm sweet for a cold winter evening": ["Gajar Ka Halwa", "Gulab Jamun"],
    "chicken cooked in a clay oven": ["Tandoori Chicken (Half)", "Murgh Malai Kebab"],
    "mild creamy curry a child would like": ["Butter Chicken", "Dal Makhani", "Kerala Vegetable Stew", "Murgh Malai Kebab"],
    "chinese noodles": ["Hakka Noodles"],
    "rice slow cooked in a sealed pot": ["Hyderabadi Chicken Dum Biryani", "Mutton Biryani", "Veg Dum Biryani"],
    "big vegetarian meal with lots of small dishes": ["Rajasthani Thali", "Mini Thali"],
    "something with caffeine": ["Filter Coffee", "Tiramisu"],
    "crunchy fried snack": ["Medu Vada", "Peri Peri Fries", "Samosa", "Chicken 65", "Fish Fry"],
    "box of sweets to give as a festival gift": ["Kaju Katli (250 g)"],
    "bread to mop up my curry": ["Garlic Naan", "Butter Roti", "Appam (2 pcs)"],
    "vegan fruit breakfast": ["Berry Smoothie Bowl", "Avocado Toast"],
}


OFF_TOPIC = [
    "quarterly tax filing", "car insurance renewal", "python programming tutorial", "weather forecast tomorrow",
    "how to fix a flat tyre", "best smartphone under 20000", "train tickets to delhi", "learn guitar chords",
]


def get_json(url):
    while True:
        try:
            with urllib.request.urlopen(url, timeout=30) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            if error.code == 429:  # rate limited: wait as told, then retry
                time.sleep(int(error.headers.get("Retry-After", "5")))
                continue
            raise


def semantic_results(base_url, query):
    params = urllib.parse.urlencode({"q": query, "limit": 10})
    return get_json(f"{base_url}/api/v1/search/dishes?{params}")["results"]


def semantic_ranking(base_url, query):
    return [result["name"] for result in semantic_results(base_url, query)]


def sweep(base_url):
    """Relevant matches kept vs off-topic queries rejected, for each candidate minimum score."""
    relevant_scores, other_scores = [], []
    for query, relevant in QUERIES.items():
        for result in semantic_results(base_url, query):
            (relevant_scores if result["name"] in relevant else other_scores).append(result["score"])
    off_topic_top = [max([r["score"] for r in semantic_results(base_url, q)] or [0]) for q in OFF_TOPIC]
    rows = []
    for step in range(68, 81):
        threshold = step / 100
        rows.append({
            "threshold": threshold,
            "relevant_kept": round(sum(s >= threshold for s in relevant_scores) / len(relevant_scores), 3),
            "other_results_kept": round(sum(s >= threshold for s in other_scores) / len(other_scores), 3),
            "off_topic_rejected": round(sum(top < threshold for top in off_topic_top) / len(off_topic_top), 3),
        })
    return {"relevant_score_min": min(relevant_scores), "off_topic_top_scores": sorted(off_topic_top), "rows": rows}


def load_dishes(base_url):
    """Every dish from the public menus, as the text the keyword baseline searches."""
    restaurants = get_json(f"{base_url}/api/v1/restaurants?latitude=26.2124&longitude=78.1772&radiusKm=20")
    dishes = {}
    for restaurant in restaurants["restaurants"]:
        menu = get_json(f"{base_url}/api/v1/restaurants/{restaurant['id']}/menu")
        for category in menu["categories"]:
            for item in category["items"]:
                dishes[item["name"]] = " ".join([
                    item["name"], item.get("description") or "", item["category"], " ".join(restaurant["cuisines"]),
                    "vegetarian" if item["vegetarian"] else "non-vegetarian", item["spiceLevel"].lower(),
                    " ".join(item.get("tags") or []), restaurant["name"]])
    return dishes


def tokens(text):
    return [word[:-1] if word.endswith("s") and len(word) > 3 else word
            for word in re.findall(r"[a-z0-9]+", text.lower()) if len(word) > 1]


def bm25_ranking(dishes, query, k1=1.5, b=0.75):
    docs = {name: tokens(text) for name, text in dishes.items()}
    average_length = sum(len(d) for d in docs.values()) / len(docs)
    document_frequency = Counter(term for d in docs.values() for term in set(d))
    scores = {}
    for name, doc in docs.items():
        counts = Counter(doc)
        score = 0.0
        for term in tokens(query):
            if term not in counts:
                continue
            idf = math.log(1 + (len(docs) - document_frequency[term] + 0.5) / (document_frequency[term] + 0.5))
            tf = counts[term]
            score += idf * tf * (k1 + 1) / (tf + k1 * (1 - b + b * len(doc) / average_length))
        if score > 0:
            scores[name] = score
    return [name for name, _ in sorted(scores.items(), key=lambda item: -item[1])][:10]


def metrics(ranking, relevant):
    relevant = set(relevant)
    first = next((rank for rank, name in enumerate(ranking[:10], 1) if name in relevant), None)
    return {
        "hit@1": 1.0 if ranking[:1] and ranking[0] in relevant else 0.0,
        "hit@3": 1.0 if any(name in relevant for name in ranking[:3]) else 0.0,
        "recall@5": len(relevant & set(ranking[:5])) / min(len(relevant), 5),
        "mrr@10": 1.0 / first if first else 0.0,
    }


def average(rows):
    return {key: round(sum(row[key] for row in rows) / len(rows), 3) for key in rows[0]}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--json-out")
    parser.add_argument("--sweep", action="store_true", help="also sweep the minimum match score")
    args = parser.parse_args()

    if args.sweep:
        result = sweep(args.base_url)
        print(f"lowest score of a relevant match: {result['relevant_score_min']}")
        print(f"top scores for off-topic queries: {result['off_topic_top_scores']}")
        print("threshold  relevant kept  other results kept  off-topic rejected")
        for row in result["rows"]:
            print(f"  {row['threshold']:.2f}       {row['relevant_kept']:.3f}          {row['other_results_kept']:.3f}"
                  f"              {row['off_topic_rejected']:.3f}")
        if args.json_out:
            with open(args.json_out, "w") as file:
                json.dump(result, file, indent=2)
        return

    dishes = load_dishes(args.base_url)
    missing = {name for names in QUERIES.values() for name in names} - dishes.keys()
    assert not missing, f"labels not found in the menus: {missing}"

    per_query, semantic_rows, keyword_rows = [], [], []
    for query, relevant in QUERIES.items():
        semantic = semantic_ranking(args.base_url, query)
        keyword = bm25_ranking(dishes, query)
        semantic_rows.append(metrics(semantic, relevant))
        keyword_rows.append(metrics(keyword, relevant))
        per_query.append({"query": query, "semantic_top3": semantic[:3], "keyword_top3": keyword[:3],
                          "semantic": semantic_rows[-1], "keyword": keyword_rows[-1]})
        print(f"{query[:48]:<48} semantic MRR {semantic_rows[-1]['mrr@10']:.2f} | keyword MRR "
              f"{keyword_rows[-1]['mrr@10']:.2f} | top: {semantic[:1]}")

    report = {"queries": len(QUERIES), "dishes": len(dishes),
              "semantic": average(semantic_rows), "keyword_bm25": average(keyword_rows), "per_query": per_query}
    print(json.dumps({key: report[key] for key in ("queries", "dishes", "semantic", "keyword_bm25")}, indent=2))
    if args.json_out:
        with open(args.json_out, "w") as file:
            json.dump(report, file, indent=2)


if __name__ == "__main__":
    main()
