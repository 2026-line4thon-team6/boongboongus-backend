-- Bearings at the mainline next to each directional rest area, rounded to whole degrees.
-- Verified against Kakao Mobility routes from Gongdeok to Daecheon Beach and Anmok Beach.
UPDATE rest_areas AS area
SET travel_bearing = verified.bearing
FROM (VALUES
    -- Seohaean Expressway, toward Daecheon Beach.
    ('rest_076f57e1d04855a139b1db97', 213), -- Maesong toward Mokpo
    ('rest_70bf2f6932f5870487bcf28b', 180), -- Hwaseong toward Mokpo
    ('rest_84bf4627c689de1e6f8851fc', 192), -- Seosan toward Mokpo
    ('rest_430ae4a31136138f8331f40d', 176), -- Hongseong toward Mokpo
    ('rest_52c65ea71981551e5e5b4f2a', 171), -- Daecheon toward Mokpo
    -- Seoul–Yangyang Expressway, recommended and fastest routes.
    ('rest_368cee29f37e40beb1982507', 102), -- Hongcheon toward Yangyang
    ('rest_b879f0532d98099f48cb343a', 274), -- Hongcheon toward Seoul
    -- Yeongdong Expressway, shortest route.
    ('rest_894b68b971fa10d651f36469', 103), -- Hoengseong toward Gangneung
    ('rest_8dad824847120cc2940b9922', 280), -- Hoengseong toward Incheon
    ('rest_3877a690ca93e27e7d92c5c2', 97),  -- Pyeongchang toward Gangneung
    ('rest_d690224a8d06b28d80cb73c8', 214)  -- Pyeongchang toward Incheon
) AS verified(id, bearing)
WHERE area.id = verified.id AND area.travel_bearing IS NULL;
