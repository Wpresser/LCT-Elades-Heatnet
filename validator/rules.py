"""Справочники техприложения ЛЦТ 2026, задача 2. Переписаны из таблиц вручную, независимо от Java-сервиса."""

# Таблица 1: ДУ -> (пропускная способность т/ч, предельная длина м, цена руб/м, ширина пары м, высота м)
DN_TABLE = {
    50: (3.5, 181, 74_023, 0.400, 0.125),
    65: (8.3, 245, 78_631, 0.430, 0.140),
    80: (13.2, 327, 83_530, 0.470, 0.160),
    100: (22.3, 419, 89_748, 0.510, 0.180),
    125: (40.2, 554, 97_275, 0.600, 0.225),
    150: (65.1, 696, 105_507, 0.650, 0.250),
    200: (152.3, 1042, 120_275, 0.880, 0.315),
    250: (274.9, 1379, 135_323, 1.050, 0.400),
    300: (437.4, 1718, 150_022, 1.150, 0.450),
    400: (943.1, 2477, 190_299, 1.370, 0.560),
    500: (1663.4, 3245, 224_137, 1.670, 0.710),
    600: (2627.7, 4037, 264_790, 1.850, 0.800),
    700: (3735.1, 4775, 324_298, 2.050, 0.900),
    800: (5296.8, 5644, 325_996, 2.250, 1.000),
    900: (7165.0, 6518, 327_693, 2.450, 1.100),
    1000: (9391.8, 7419, 418_777, 2.650, 1.200),
    1200: (15012.8, 9288, 428_074, 3.100, 1.425),
    1400: (22501.9, 11276, 683_417, 3.450, 1.600),
}
DNS = sorted(DN_TABLE)


def capacity(dn):
    return DN_TABLE[dn][0]


def max_length(dn):
    return DN_TABLE[dn][1]


def price(dn):
    return DN_TABLE[dn][2]


def half_width(dn):
    return DN_TABLE[dn][3] / 2.0


def half_width_existing(dn):
    """Ширина существующей трубы: ДУ из таблицы или ближайший больший (консервативно)."""
    for d in DNS:
        if d >= dn:
            return DN_TABLE[d][3] / 2.0
    return DN_TABLE[DNS[-1]][3] / 2.0


def min_dn_by_flow(flow):
    for d in DNS:
        if capacity(d) >= flow:
            return d
    return None


# Таблица 2. kind: forbidden | area (спецпроход через площадной объект) | crossing (спецпроход через линию)
RESTRICTIONS = {
    "oks": dict(kind="forbidden", clearance=None, angle=0, margin=0, k=1.0, own_width=0.0),
    "park": dict(kind="forbidden", clearance=1.0, angle=0, margin=0, k=1.0, own_width=0.0),
    "social_area": dict(kind="forbidden", clearance=1.0, angle=0, margin=0, k=1.0, own_width=0.0),
    "prohibited_site": dict(kind="forbidden", clearance=1.0, angle=0, margin=0, k=1.0, own_width=0.0),
    "water": dict(kind="forbidden", clearance=1.0, angle=0, margin=0, k=1.0, own_width=0.0),
    "railway": dict(kind="forbidden", clearance=1.0, angle=0, margin=0, k=1.0, own_width=0.0),
    "road": dict(kind="area", clearance=1.5, angle=45, margin=3.0, k=1.60, own_width=0.0),
    "tram_tracks": dict(kind="area", clearance=1.5, angle=45, margin=3.0, k=1.75, own_width=0.0),
    "gas_pipeline": dict(kind="crossing", clearance=2.0, angle=0, margin=2.0, k=1.25, own_width=0.40),
    "power_cable": dict(kind="crossing", clearance=2.0, angle=0, margin=2.0, k=1.15, own_width=0.20),
}
# Пересечение существующей теплосети без врезки (таблица 2, разъяснение №10); габарит — по ДУ трубы
EXISTING_HEAT_NETWORK = dict(kind="crossing", clearance=1.0, angle=0, margin=2.0, k=1.05, own_width=None)


def clearance(rtype, dn):
    if rtype == "oks":
        if dn < 500:
            return 5.0
        if dn <= 800:
            return 7.0
        return 9.0
    return RESTRICTIONS[rtype]["clearance"]


def new_chamber_cost(max_dn):
    if max_dn <= 200:
        return 3_000_000
    if max_dn <= 500:
        return 5_000_000
    if max_dn <= 1000:
        return 8_000_000
    return 12_000_000


TIE_IN_COST = 5_000_000


def penalty(flow):
    return 100_000_000 + 500_000 * flow


def score(calculated_cost, length):
    return 0.7 * (calculated_cost / 25_000_000) + 0.3 * (length / 100)
