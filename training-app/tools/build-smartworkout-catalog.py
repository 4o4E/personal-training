#!/usr/bin/env python3
"""Build server-only SmartWorkout guides and additive exercise seed data."""
from __future__ import annotations

import hashlib
import json
import unicodedata
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "data" / "smartworkout" / "source.json"
CATALOG = ROOT / "catalog" / "catalog.json"
GUIDES = ROOT / "server" / "src" / "main" / "resources" / "smartworkout-guides.json"
DIRECTORY = ROOT / "server" / "src" / "main" / "resources" / "smartworkout-directory.json"
EQUIPMENT_TAGS = ROOT / "catalog" / "equipment-tags.json"
SOURCE_GUIDES = ROOT / "server" / "src" / "main" / "resources" / "smartworkout-source-guides.json"
SELECTIONS = ROOT / "server" / "src" / "main" / "resources" / "smartworkout-selections.json"
ASSOCIATIONS = ROOT / "server" / "src" / "main" / "resources" / "smartworkout-associations.json"
FULL_SOURCE = ROOT / "server" / "src" / "main" / "resources" / "smartworkout-source.json"
REPORT = ROOT / "server" / "src" / "main" / "resources" / "smartworkout-association-report.json"

# Only explicit source labels are translated. Unknown equipment remains unmatched,
# preventing a name-only collision from being treated as the same exercise.
EQUIPMENT_LABELS = {
    "barbell": "barbell", "杠铃": "barbell", "dumbbell": "dumbbell", "dumbbells": "dumbbell", "哑铃": "dumbbell",
    "flat bench": "flat_bench", "平板训练凳": "flat_bench", "adjustable bench": "adjustable_bench", "可调训练凳": "adjustable_bench",
    "cable": "cable", "绳索滑轮": "cable", "resistance band": "resistance_band", "弹力带": "resistance_band",
    "suspension trainer": "suspension_trainer", "悬吊训练带": "suspension_trainer", "kettlebell": "kettlebell", "壶铃": "kettlebell",
    "plate": "plate", "杠铃片": "plate", "smith machine": "smith_machine", "史密斯机": "smith_machine",
    "leg press": "leg_press", "腿举机": "leg_press", "leg extension": "leg_extension", "腿屈伸机": "leg_extension",
    "leg curl": "leg_curl", "腿弯举机": "leg_curl", "seated leg curl": "seated_leg_curl", "坐姿腿弯举机": "seated_leg_curl",
    "chest press machine": "chest_press_machine", "胸推机": "chest_press_machine", "chest fly machine": "chest_fly_machine", "夹胸机": "chest_fly_machine",
    "reverse fly machine": "reverse_fly_machine", "反向飞鸟机": "reverse_fly_machine", "shoulder press machine": "shoulder_press_machine", "推肩机": "shoulder_press_machine",
    "row machine": "row_machine", "坐姿划船机": "row_machine", "lat pulldown machine": "lat_pulldown_machine", "高位下拉机": "lat_pulldown_machine",
    "assisted pullup machine": "assisted_pullup_machine", "助力引体机": "assisted_pullup_machine", "assisted dip machine": "assisted_dip_machine", "助力双杠机": "assisted_dip_machine",
    "standing calf raise machine": "standing_calf_raise_machine", "站姿提踵机": "standing_calf_raise_machine", "seated calf raise machine": "seated_calf_raise_machine", "坐姿提踵机": "seated_calf_raise_machine",
    "bicep curl machine": "bicep_curl_machine", "弯举机": "bicep_curl_machine", "tricep extension machine": "tricep_extension_machine", "三头臂屈伸机": "tricep_extension_machine",
    "pull up bar": "pull_up_bar", "引体横杆": "pull_up_bar", "dip station": "dip_station", "双杠架": "dip_station", "wall": "wall", "墙面或固定支撑": "wall",
    "cable machine": "cable", "cable_machine": "cable", "钢索机": "cable", "weight_plate": "plate",
    "bodyweight": "", "body weight": "", "自重": "", "无器械": "", "none": "", "": "",
}

CURATED_ASSOCIATIONS: dict[str, tuple[str, str, str, tuple[str, ...]]] = {
    # Curated pairs are emitted with source steps and local catalog fields for review.
    "smartworkout_41b2698697e82c4c": ("俯卧撑", "exercise_392887aa", "strength", ()),
    "smartworkout_97fe148c10de3c3b": ("单臂俯卧撑", "exercise_b140095e", "strength", ()),
    "smartworkout_7a8a53f8b861f657": ("跪姿俯卧撑", "exercise_b74a95bb", "strength", ()),
    "smartworkout_a8c187feef30c8ff": ("倒立俯卧撑", "exercise_90b04f96", "strength", ()),
    "smartworkout_ca9397ed084ac7fc": ("钻石俯卧撑", "exercise_6575f52d", "strength", ()),
    "smartworkout_401e0e6f32fa67bf": ("死虫式", "exercise_d8911fc4", "strength", ()),
    "smartworkout_3d973a4282bbcf93": ("仰卧起坐", "exercise_022df610", "strength", ()),
    "smartworkout_2e4ada0a40e9f453": ("侧平板支撑", "exercise_e3eda509", "strength", ()),
    "smartworkout_4be979acdef4e99f": ("反向卷腹", "exercise_7952b5cd", "strength", ()),
    "smartworkout_7d6efd01da117603": ("平板支撑", "exercise_c6c9b8a0", "strength", ()),
    "smartworkout_2265210d531f2f83": ("下犬式", "exercise_7da843a3", "strength", ()),
    "smartworkout_f446a45dbbbc0e3e": ("单腿深蹲", "exercise_3ff6a22e", "strength", ()),
    "smartworkout_8857fe5bc50d9544": ("反向平板支撑", "exercise_99b49684", "strength", ()),
    "smartworkout_6f24337cf1825691": ("开合跳", "exercise_991833c2", "strength", ()),
    "smartworkout_4ca623d7bfb1ee56": ("波比跳", "exercise_bb792a36", "strength", ()),
    "smartworkout_6f4c2a1417825b02": ("超人式", "exercise_218da87c", "strength", ()),
    "smartworkout_2c1d4c2f412dd710": ("单腿臀桥", "exercise_cbc0d834", "strength", ()),
    "smartworkout_9f1b074eac433a1e": ("臀桥", "exercise_cda23948", "strength", ()),
    "smartworkout_6425395f3bbaf303": ("鸟狗式", "exercise_bd0ad077", "strength", ()),
    "smartworkout_61ced31958ff6b4a": ("宽握引体向上", "exercise_7c50f118", "strength", ("PULL_UP_BAR",)),
    "smartworkout_6bbc01eb84a75b7e": ("引体向上", "exercise_1b2b1e7c", "strength", ("PULL_UP_BAR",)),
    "smartworkout_31e7265aa7708502": ("下斜俯卧撑", "exercise_c43825ea", "strength", ("PLYO_BOX", "BENCH")),
    "smartworkout_36dba441a648156b": ("吊环俯卧撑", "exercise_aea56bdc", "strength", ("GYMNASTIC_RINGS",)),
    "smartworkout_8883281d871f28c5": ("双杠臂屈伸", "exercise_6fcd7755", "strength", ("DIP_BARS",)),
    "smartworkout_3971548016bdcd40": ("药球砸地", "exercise_f51d9080", "strength", ("MEDICINE_BALL",)),
    "smartworkout_4000c5f557bb6d7e": ("跳箱", "exercise_56092dd1", "strength", ("PLYO_BOX",)),
    "smartworkout_a6efe550a96fb8e6": ("靠墙静蹲", "exercise_c8706c80", "strength", ()),
    "smartworkout_e5f8c00766dd814b": ("蝴蝶机夹胸", "exercise_9dce2d64", "strength", ("SELECTORIZED_MACHINE",)),
    "smartworkout_6ee4d908c3abb828": ("坐姿提踵", "exercise_062ab91a", "strength", ("PLATE_LOADED_MACHINE",)),
    "smartworkout_8b108ab7b4480408": ("箱式深蹲", "exercise_f2fce72d", "strength", ("BARBELL", "PLYO_BOX")),
    "smartworkout_25ffb49c96aa555a": ("高抬腿跑", "exercise_150e076b", "strength", ()),
    "smartworkout_5a86dadb2e629613": ("哑铃高脚杯深蹲", "exercise_3d0c7c75", "strength", ("DUMBBELL",)),
    "smartworkout_9f08b8cc572250eb": ("单腿罗马尼亚硬拉", "exercise_937292ab", "strength", ("DUMBBELL", "KETTLEBELL", "BARBELL")),
    "smartworkout_a21ab312bab32eed": ("哑铃侧平举", "exercise_422b08f1", "strength", ("DUMBBELL",)),
    "smartworkout_27d8a0871ce4f0d8": ("器械反向飞鸟", "exercise_d8281c62", "strength", ("PLATE_LOADED_MACHINE", "SELECTORIZED_MACHINE")),
    "smartworkout_000234381e70636e": ("坐姿腿屈伸", "exercise_75a4f6c4", "strength", ("SELECTORIZED_MACHINE",)),
    "smartworkout_30d17dcf76b93d79": ("直杆绳索弯举", "exercise_ada8623c", "strength", ("CABLE_MACHINE",)),
    "smartworkout_0329dbcac32323a7": ("高位下拉", "exercise_6a6c31a5", "strength", ("CABLE_MACHINE",)),
    "smartworkout_2f8954bca758706b": ("佐特曼弯举", "exercise_123ee239", "strength", ("DUMBBELL",)),
    "smartworkout_f287859dccda3e02": ("俯卧腿弯举", "exercise_b8127ad1", "strength", ("PLATE_LOADED_MACHINE", "SELECTORIZED_MACHINE")),
    "smartworkout_a9ec048a7a663917": ("单腿坐姿腿屈伸", "exercise_629ae73d", "strength", ("PLATE_LOADED_MACHINE", "SELECTORIZED_MACHINE")),
    "smartworkout_f71bb5d6c3164f83": ("坐姿腿弯举", "exercise_11a123f3", "strength", ("PLATE_LOADED_MACHINE",)),
    "smartworkout_2db88f35cbd34128": ("单腿腿举", "exercise_3fd83744", "strength", ("PLATE_LOADED_MACHINE", "SELECTORIZED_MACHINE")),
    "smartworkout_b3afffdd8b362da9": ("单臂三头肌下压", "exercise_552ab030", "strength", ("CABLE_MACHINE",)),
    "smartworkout_e0ce5d1887aa501e": ("低位绳索夹胸", "exercise_293483ad", "strength", ("CABLE_MACHINE",)),
    "smartworkout_ee33fea5baf4ebdd": ("单臂绳索低位划船", "exercise_d0c4a899", "strength", ("CABLE_MACHINE",)),
    "smartworkout_36f11282ba58d609": ("杠铃直腿硬拉", "exercise_2a48e443", "strength", ("BARBELL",)),
    "smartworkout_201acedbd0c55249": ("罗马尼亚硬拉", "exercise_2b4b7310", "strength", ("BARBELL",)),
    "smartworkout_d3f173bf8368e7e4": ("杠铃借力推举", "exercise_542f3cd5", "strength", ("BARBELL",)),
    "smartworkout_6f41876bb3d323d7": ("杠铃架上硬拉", "exercise_fe389074", "strength", ("BARBELL", "RACK")),
    "smartworkout_596c690b31373454": ("杠铃过头深蹲", "exercise_2cfed196", "strength", ("BARBELL",)),
    "smartworkout_694e6446242d8521": ("单臂地雷管俯身划船", "exercise_c732c341", "strength", ("LANDMINE",)),
    "smartworkout_345621e4fb4b2a60": ("杠铃哈克深蹲", "exercise_ac5a4c58", "strength", ("BARBELL",)),
    "smartworkout_ced3d0d66bf0688b": ("哑铃集中弯举", "exercise_724cde60", "strength", ("DUMBBELL", "BENCH")),
    "smartworkout_e7a69160c18d5fa1": ("跪姿髋屈肌拉伸", "stretch_kneeling-hip-flexor-stretch", "stretch", ()),
    "smartworkout_04c6468edd7b1942": ("站立股四头肌拉伸", "stretch_standing-quad-stretch", "stretch", ()),
    "smartworkout_8c8b14a09eb2d4ef": ("过头三头肌拉伸", "stretch_overhead-triceps-stretch", "stretch", ()),
    "smartworkout_2414ba0f9ca52b53": ("婴儿式", "stretch_childs-pose", "stretch", ()),
    "smartworkout_70677873201f65c2": ("靠墙小腿拉伸", "stretch_standing-calf-stretch", "stretch", ()),
    "smartworkout_65619f30242a9e3e": ("猫牛式", "stretch_cat-cow", "stretch", ()),
    "smartworkout_0ee9d8c41edcc4d9": ("鸽子式", "stretch_pigeon-stretch", "stretch", ()),
    "smartworkout_b72fd9a5861bdb8c": ("蝴蝶伸展", "stretch_butterfly-stretch", "stretch", ()),
    "smartworkout_f332bd0f2324399b": ("仰卧屈膝抱胸拉伸", "stretch_knee-to-chest-stretch", "stretch", ()),
}
BODYWEIGHT_ASSOCIATION_REASON = (
    "来源器械类别为空，未把空值当作无器械；来源步骤与本地步骤都明确描述同名徒手/地面动作，"
    "且既有动作equipmentTags为空。"
)


MUSCLE_LABELS = {
    "CHEST": "chest", "CHEST_LOWER": "chest", "CHEST_MIDDLE": "chest", "CHEST_UPPER": "chest", "PECTORALS": "chest",
    "TRICEPS": "triceps", "TRICEPS_LONG_HEAD": "triceps", "TRICEPS_LATERAL_HEAD": "triceps", "TRICEPS_MEDIAL_HEAD": "triceps",
    "FRONT_DELTOIDS": "shoulders", "BACK_DELTOIDS": "shoulders", "SHOULDERS": "shoulders",
    "SHOULDERS_FRONT_PART": "shoulders", "SHOULDERS_MIDDLE_PART": "shoulders", "SHOULDERS_REAR_PART": "shoulders", "DELTOIDS": "shoulders",
    "UPPER_BACK": "upper_back", "BACK_INFRASPINATUS": "upper_back", "BACK_TERES_MAJOR": "upper_back", "BACK_TERES_MINOR": "upper_back",
    "LATS": "lats", "LATISSIMUS_DORSI": "lats", "BACK_LATS": "lats", "BICEPS": "biceps",
    "BICEPS_LONG_HEAD": "biceps", "BICEPS_SHORT_HEAD": "biceps", "BICEPS_FEMORIS": "hamstrings",
    "FOREARM": "forearms", "FOREARMS": "forearms", "FOREARM_EXTENSORS": "forearms", "FOREARM_FLEXORS": "forearms", "BRACHIORADIALIS": "forearms",
    "LOWER_BACK": "lower_back", "ERECTOR_SPINAE": "lower_back", "HAMSTRING": "hamstrings", "HAMSTRINGS": "hamstrings",
    "SEMIMEMBRANOSUS": "hamstrings", "SEMITENDINOSUS": "hamstrings", "GLUTEAL": "glutes", "GLUTES": "glutes",
    "GLUTEUS_MAXIMUS": "glutes", "GLUTEUS_MEDIUS": "abductors", "QUADRICEPS": "quadriceps",
    "QUADRICEPS_RECTUS_FEMORIS": "quadriceps", "QUADRICEPS_VASTUS_LATERALIS": "quadriceps", "QUADRICEPS_VASTUS_MEDIALIS": "quadriceps",
    "ABS": "abdominals", "ABDOMINALS": "abdominals", "ABS_LOWER": "abdominals", "ABS_OBLIQUES": "abdominals", "ABS_UPPER": "abdominals", "OBLIQUES": "abdominals",
    "TRAPEZIUS": "traps", "TRAPS": "traps", "BACK_TRAPEZIUS_LOWER": "traps", "BACK_TRAPEZIUS_MIDDLE": "traps", "BACK_TRAPEZIUS_UPPER": "traps",
    "CALVES": "calves", "LEFT_SOLEUS": "calves", "RIGHT_SOLEUS": "calves", "NECK": "neck",
    "ADDUCTOR": "adductors", "ADDUCTORS": "adductors", "ABDUCTORS": "abductors", "HIP_FLEXORS": "hip_flexors",
    "FULL_BODY": "full_body", "OTHER": "other",
}


def normalize(value: str) -> str:
    value = unicodedata.normalize("NFKC", value).casefold()
    return "".join(char for char in value if char.isalnum())


def valid_web_url(value: str) -> bool:
    try:
        parsed = urlsplit(value)
        return parsed.scheme.lower() in {"http", "https"} and bool(parsed.hostname)
    except ValueError:
        return False


def strings(value: Any) -> list[str]:
    if isinstance(value, str):
        return [value] if value.strip() else []
    if isinstance(value, list):
        return [item for child in value for item in strings(child)]
    if isinstance(value, dict):
        return [item for child in value.values() for item in strings(child)]
    return []


def require_source() -> tuple[dict, list[dict], list[dict]]:
    source = json.loads(SOURCE.read_text(encoding="utf-8"))
    if not isinstance(source, dict) or not isinstance(source.get("exercises"), list):
        raise ValueError("source.json 缺少 exercises 列表")
    cross = source.get("sitemapCrossCheck") or {}
    expected_source_count = cross.get("sitemapUniqueUrlCount")
    if not isinstance(expected_source_count, int) or expected_source_count <= 0 or \
            source.get("failures") or cross.get("successfulDetailCount") != expected_source_count or \
            cross.get("missingDetailCount") != 0 or cross.get("failedDetailCount") != 0 or \
            cross.get("completeRawSourceRecordCount") != expected_source_count or \
            cross.get("missingRawSourceRecordCount") != 0 or \
            cross.get("duplicateSitemapUrlCount") != 0 or cross.get("detailCanonicalUrlsNotInSitemap"):
        raise ValueError(f"源数据尚未完成详情交叉核对或仍有失败：{cross}")
    if len(source["exercises"]) != expected_source_count:
        raise ValueError(f"source.json 动作记录数与核验详情数不符：{len(source['exercises'])} != {expected_source_count}")
    if any(not isinstance(item.get("sourceExercise"), dict) or not isinstance(item.get("sourcePageData"), dict)
           or not isinstance(item.get("pageMetadata"), dict) for item in source["exercises"]):
        raise ValueError("source.json 含未采集完整原始 exercise/pageProps 的动作")
    catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
    existing = [item for item in catalog if item.get("kind") == "exercise" and not item.get("deleted")]
    muscles = [item for item in catalog if item.get("kind") == "muscle" and not item.get("deleted")]
    return source, existing, muscles


def source_names(item: dict) -> set[str]:
    candidates = [item.get("names", {}).get("zh"), item.get("names", {}).get("en"), item.get("name")]
    candidates += strings(item.get("aliases"))
    return {normalize(name) for name in candidates if isinstance(name, str) and normalize(name)}


def current_equipment(data: dict) -> set[str] | None:
    tags = data.get("equipmentTags")
    if not isinstance(tags, list):
        return None
    return set(tags)


def source_equipment(item: dict, known_tags: set[str]) -> set[str] | None:
    raw_equipment = item.get("equipmentCategories")
    if isinstance(raw_equipment, list) and not raw_equipment:
        return None
    raw_values = strings(raw_equipment)
    if not raw_values:
        return None
    result: set[str] = set()
    normalized_tags = {tag.casefold(): tag for tag in known_tags}
    for raw in raw_values:
        text = raw.strip()
        normalized = text.casefold()
        if normalized in normalized_tags:
            result.add(normalized_tags[normalized])
            continue
        mapped = EQUIPMENT_LABELS.get(normalized)
        if mapped is None or mapped and mapped not in known_tags:
            return None
        if mapped:
            result.add(mapped)
    return result


def source_load_mode(item: dict) -> str | None:
    weight_type = normalize(str(item.get("weightType") or ""))
    if weight_type in {"bodyweight", "bodyweightonly", "bodyweightbased", "selfweight", "unweighted", "自重", "自身体重"}:
        return "bodyweight"
    if weight_type in {"band", "barbell", "dumbbell", "weighted", "assistedweight", "external", "externalload", "externalweight", "loaded", "machine", "machinebased", "负重", "外部负重", "器械"}:
        return "external"
    return None




def source_unilateral(item: dict) -> bool | None:
    laterality = normalize(str(item.get("laterality") or ""))
    if laterality in {"unilateral", "single", "singlearm", "singleleg", "单侧", "左右分别"}:
        return True
    if laterality in {"bilateral", "both", "双侧", "双臂", "双腿"}:
        return False
    return None


def local_type(item: dict) -> str | None:
    value = item.get("data", {}).get("type")
    return value if value in {"strength", "stretch"} else None


def local_names(item: dict) -> set[str]:
    name = item.get("data", {}).get("name")
    return {normalize(name)} if isinstance(name, str) and normalize(name) else set()


def muscle_assignments(item: dict, known_muscles: set[str]) -> tuple[list[str] | None, list[str], str | None]:
    raw = item.get("muscles") or {}
    body_part = raw.get("bodyPart")
    body_primary = MUSCLE_LABELS.get(str(body_part).upper()) if body_part is not None else None
    if isinstance(body_part, str) and body_part in known_muscles:
        body_primary = body_part

    scores = raw.get("scores") or {}
    ranked: list[tuple[float, str]] = []
    if isinstance(scores, dict):
        for source_id, value in scores.items():
            mapped = MUSCLE_LABELS.get(str(source_id).upper())
            if mapped not in known_muscles:
                continue
            try:
                score = float(value)
            except (TypeError, ValueError):
                continue
            ranked.append((score, mapped))

    if ranked:
        top_score = max(score for score, _ in ranked)
        leaders = {muscle for score, muscle in ranked if score == top_score}
        if len(leaders) == 1:
            primary = next(iter(leaders))
            if body_primary in known_muscles and body_primary != primary:
                return None, [], "源 bodyPart 与最高肌群分数冲突"
        elif body_primary in leaders:
            primary = body_primary
        else:
            return None, [], "源肌群最高分并列且 bodyPart 不能消除歧义"
    elif body_primary in known_muscles:
        primary = body_primary
    else:
        return None, [], "源肌群没有可映射的主要肌群"

    secondary: list[str] = []
    for _, muscle in sorted(ranked, key=lambda pair: (-pair[0], pair[1])):
        if muscle != primary and muscle not in secondary:
            secondary.append(muscle)
    return [primary], secondary, None


def exercise_id_for_source(item: dict) -> str:
    source_url = str(item.get("sourceUrl") or item.get("sitemapUrl") or "")
    digest = hashlib.sha256(source_url.encode("utf-8")).hexdigest()[:16]
    return f"smartworkout_{digest}"

def selection_mapping(item: dict, known_muscles: set[str], known_tags: set[str]) -> dict:
    muscles, secondary, _ = muscle_assignments(item, known_muscles)
    if muscles is None:
        muscles = ["other"]

    equipment = source_equipment(item, known_tags)
    equipment_tags = sorted(equipment) if equipment is not None else ["source_unknown"]
    load_mode = source_load_mode(item) or "external"
    unilateral = source_unilateral(item)

    return {
        "muscles": muscles,
        "secondaryMuscles": secondary,
        "equipmentTags": equipment_tags,
        "unilateral": unilateral if unilateral is not None else False,
        "bodyweight": load_mode == "bodyweight",
        "loadMode": load_mode,
    }



def main() -> None:
    source, existing, muscles = require_source()
    equipment_labels = json.loads(EQUIPMENT_TAGS.read_text(encoding="utf-8"))
    if not isinstance(equipment_labels, dict) or any(
        not isinstance(key, str) or not isinstance(value, str) for key, value in equipment_labels.items()
    ):
        raise ValueError("equipment-tags.json必须是字符串到字符串的对象")
    existing_by_id = {entry["id"]: entry for entry in existing}
    if len(existing_by_id) != len(existing):
        raise ValueError("catalog.json含重复既有动作标识")
    known_muscles = {entry["id"] for entry in muscles}
    known_tags = {tag for entry in existing for tag in entry.get("data", {}).get("equipmentTags", [])}
    contexts = ("strength", "stretch")
    directory: list[dict] = []
    selection_mappings: dict[str, dict] = {}
    pair_candidates: dict[str, list[dict]] = {}
    pair_sources: dict[str, dict] = {}
    source_items_by_id: dict[str, dict] = {}
    matched_curated_ids: set[str] = set()
    seen_source_urls: set[str] = set()
    seen_source_ids: set[str] = set()

    for item in source["exercises"]:
        if not isinstance(item, dict):
            raise ValueError("source.json exercises 含非对象记录")
        source_url = item.get("sourceUrl")
        if not isinstance(source_url, str) or not valid_web_url(source_url):
            raise ValueError(f"动作缺少有效 sourceUrl：{item!r}")
        if source_url in seen_source_urls:
            raise ValueError(f"source.json 含重复详情 URL：{source_url}")
        seen_source_urls.add(source_url)

        source_id = exercise_id_for_source(item)
        if source_id in seen_source_ids:
            raise ValueError(f"source.json 含重复 sourceId：{source_id}")
        seen_source_ids.add(source_id)
        source_items_by_id[source_id] = item
        names = item.get("names") or {}
        muscle_data = item.get("muscles") or {}
        categories = item.get("categories")
        equipment = item.get("equipmentCategories")
        steps = item.get("steps")
        videos = item.get("videoUrls")
        if not isinstance(categories, list) or not all(isinstance(value, str) for value in categories):
            raise ValueError(f"动作 {source_id} 的原分类不是字符串数组")
        if not isinstance(equipment, list) or not all(isinstance(value, str) for value in equipment):
            raise ValueError(f"动作 {source_id} 的器械分类不是字符串数组")
        if not isinstance(steps, list) or not all(isinstance(value, str) for value in steps):
            raise ValueError(f"动作 {source_id} 的 steps 不是字符串数组")
        if not isinstance(videos, list) or not all(isinstance(value, str) for value in videos):
            raise ValueError(f"动作 {source_id} 的 videoUrls 不是字符串数组")

        image_url = item.get("imageUrl")
        description = item.get("description") or ""
        tips = item.get("tips") or []
        mistakes = item.get("commonMistakes") or []
        if not isinstance(description, str) or not isinstance(tips, list) or not isinstance(mistakes, list) or \
                any(not isinstance(text, str) for text in tips + mistakes):
            raise ValueError(f"动作 {source_id} 的 description/tips/commonMistakes 类型错误")
        for field, value in (("sourceUrl", source_url), ("imageUrl", image_url),
                             ("videoUrl", videos[0] if videos else None)):
            if value is not None and (not isinstance(value, str) or not valid_web_url(value)):
                raise ValueError(f"动作 {source_id} 的 {field} 不是有效HTTP(S)网址")

        mapped_primary, mapped_secondary, _ = muscle_assignments(item, known_muscles)
        mapped_muscles = mapped_primary + mapped_secondary if mapped_primary is not None else None
        mapped_equipment = source_equipment(item, known_tags)
        directory_item = {
            "sourceId": source_id,
            "name": names.get("zh") or names.get("en") or "",
            "categories": categories,
            "sourceBodyPart": muscle_data.get("bodyPart") or "",
            "mappedMuscles": mapped_muscles,
            "equipmentCategories": equipment,
            "mappedEquipmentTags": sorted(mapped_equipment) if mapped_equipment is not None else None,
        }
        directory.append(directory_item)
        selection_mappings[source_id] = selection_mapping(item, known_muscles, known_tags)

        source_name_set = source_names(item)
        source_equipment_set = source_equipment(item, known_tags)
        curated = CURATED_ASSOCIATIONS.get(source_id)
        if curated is not None:
            expected_name, target_id, target_context, expected_equipment = curated
            if directory_item["name"] != expected_name or tuple(equipment) != expected_equipment:
                raise ValueError(f"人工核验关联的来源名称/器械已变化：{source_id}")
            candidate = existing_by_id.get(target_id)
            if candidate is None or local_type(candidate) != target_context:
                raise ValueError(f"人工核验关联的本地动作不存在或类型已变化：{source_id} -> {target_id}")
        for context in contexts:
            pair_key = f"{source_id}:{context}"
            uses_curated = curated is not None and context == curated[2]
            pair_sources[pair_key] = {
                "sourceId": source_id,
                "sourceUrl": source_url,
                "sourceName": directory_item["name"],
                "contextType": context,
                "sourceCategories": categories,
                "sourceEquipmentCategories": equipment,
                "equipmentMapped": source_equipment_set is not None,
                "curatedAssociation": uses_curated,
            }
            if uses_curated:
                pair_candidates[pair_key] = [candidate]
                matched_curated_ids.add(source_id)
            elif source_equipment_set is None:
                pair_candidates[pair_key] = []
            else:
                pair_candidates[pair_key] = [
                    candidate for candidate in existing
                    if local_type(candidate) == context
                    and source_name_set & local_names(candidate)
                    and current_equipment(candidate.get("data", {})) == source_equipment_set
                ]
    if set(CURATED_ASSOCIATIONS) != matched_curated_ids:
        raise ValueError(
            f"人工核验关联未全部匹配：{sorted(set(CURATED_ASSOCIATIONS) - matched_curated_ids)}"
        )

    candidate_usage: dict[str, int] = {}
    for candidates in pair_candidates.values():
        for candidate in candidates:
            candidate_usage[candidate["id"]] = candidate_usage.get(candidate["id"], 0) + 1

    associations: dict[str, str] = {}
    report: list[dict] = []
    for pair_key, record in pair_sources.items():
        candidates = pair_candidates[pair_key]
        if len(candidates) == 1 and candidate_usage.get(candidates[0]["id"]) == 1:
            matched = candidates[0]
            exercise_id = matched["id"]
            decision = "reused_existing"
            associations[pair_key] = exercise_id
            if record["curatedAssociation"]:
                local_tags = matched.get("data", {}).get("equipmentTags", [])
                if not record["sourceEquipmentCategories"] and local_tags:
                    evidence = [
                        "来源类别未列器械，但来源步骤、本地步骤和现有专用标签释义均明确指向同一支撑物"
                    ]
                elif not record["sourceEquipmentCategories"]:
                    evidence = [BODYWEIGHT_ASSOCIATION_REASON]
                else:
                    evidence = [
                        "报告将来源中英文名称、器械类别与步骤，以及既有名称、器械标签释义与本地步骤逐项列出对照；字段支持动作及器械关联"
                    ]
            else:
                evidence = [
                    "动作名称/来源别名与既有名称完全一致",
                    "源器械分类与既有器械标签完全一致",
                ]
            evidence.append(f"既有动作类型与当前添加上下文一致：{record['contextType']}")
        else:
            exercise_id = f"{record['sourceId']}_{record['contextType']}"
            decision = "created_on_selection"
            if len(candidates) > 1:
                evidence = ["名称与器械对应多个既有动作；保持独立"]
            elif candidates and candidate_usage.get(candidates[0]["id"], 0) > 1:
                evidence = ["同一既有动作对应多个来源详情；保持独立"]
            elif candidates:
                evidence = ["候选未通过唯一关联检查；保持独立"]
            elif not record["sourceEquipmentCategories"]:
                evidence = ["来源未列器械类别；不能证明与本地无器械动作相同"]
            elif not record["equipmentMapped"]:
                evidence = ["来源器械分类无法映射；不将名称相同视为可靠关联"]
            else:
                evidence = ["未找到名称与器械均一致的既有动作"]
        report_entry = {
            **record,
            "decision": decision,
            "exerciseId": exercise_id,
            "candidateExerciseIds": [candidate["id"] for candidate in candidates],
            "evidence": evidence,
        }
        if decision == "reused_existing":
            source_item = source_items_by_id[record["sourceId"]]
            local_data = matched.get("data", {})
            local_tags = local_data.get("equipmentTags", [])
            report_entry["associationEvidence"] = {
                "sourceChineseName": source_item.get("names", {}).get("zh"),
                "sourceEnglishName": source_item.get("names", {}).get("en"),
                "sourceSteps": source_item.get("steps", []),
                "existingExerciseId": exercise_id,
                "existingName": local_data.get("name"),
                "existingEquipmentTags": local_tags,
                "existingEquipmentTagLabels": [equipment_labels.get(tag, tag) for tag in local_tags],
                "existingInstructions": local_data.get("instructions", []),
            }
        report.append(report_entry)

    guides: dict[str, dict] = {}
    source_guides: dict[str, dict] = {}
    for item, directory_item in zip(source["exercises"], directory):
        source_id = directory_item["sourceId"]
        videos = item["videoUrls"]
        guide = {
            "sourceUrl": item["sourceUrl"],
            "imageUrl": item.get("imageUrl"),
            "videoUrl": videos[0] if videos else None,
            "description": item.get("description") or "",
            "tips": item.get("tips") or [],
            "mistakes": item.get("commonMistakes") or [],
            "instructions": item["steps"],
        }
        source_guides[source_id] = guide
        for context in contexts:
            exercise_id = associations.get(f"{source_id}:{context}", f"{source_id}_{context}")
            existing_guide = guides.get(exercise_id)
            if existing_guide is not None and existing_guide != guide:
                raise ValueError(f"多个来源动作关联同一应用动作 ID：{exercise_id}")
            guides[exercise_id] = guide

    outputs = (
        (FULL_SOURCE, source),
        (DIRECTORY, directory),
        (SOURCE_GUIDES, source_guides),
        (SELECTIONS, selection_mappings),
        (ASSOCIATIONS, associations),
        (GUIDES, guides),
    )
    for path, payload in outputs:
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary = path.with_suffix(path.suffix + ".part")
        temporary.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        temporary.replace(path)

    counts: dict[str, int] = {}
    for entry in report:
        counts[entry["decision"]] = counts.get(entry["decision"], 0) + 1
    reused_entries = [entry for entry in report if entry["decision"] == "reused_existing"]
    linked_ids = sorted({entry["exerciseId"] for entry in reused_entries})
    report_data = {
        "source": str(SOURCE.relative_to(ROOT)),
        "collectedAt": source.get("collectedAt"),
        "sourceCount": len(directory),
        "selectionContextCount": len(report),
        "existingExerciseCount": len(existing),
        "reusedExistingCount": len(reused_entries),
        "curatedReviewedCount": sum(entry["curatedAssociation"] for entry in reused_entries),
        "strictNameAndEquipmentMatchCount": sum(not entry["curatedAssociation"] for entry in reused_entries),
        "reusedExistingExerciseCount": len(linked_ids),
        "reusedExistingExerciseIds": linked_ids,
        "createdOnSelectionCount": counts.get("created_on_selection", 0),
        "entries": report,
    }
    REPORT.parent.mkdir(parents=True, exist_ok=True)
    temporary = REPORT.with_suffix(REPORT.suffix + ".part")
    temporary.write_text(json.dumps(report_data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(REPORT)
    print(json.dumps({key: value for key, value in report_data.items() if key != "entries"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
