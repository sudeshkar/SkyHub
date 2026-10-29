-- Seed timetable. Designed so every connection rule has a case (docs/design/02-lld.md §7).
-- operating_days uses ISO day numbers: 1 = Monday ... 7 = Sunday.
INSERT INTO flight_schedule
    (flight_number, origin, destination, departure_time_utc, duration_minutes, aircraft_type, fare_from, currency, operating_days)
VALUES
    ('SK501', 'CMB', 'DXB', '02:00', 290, 'A350', 310.00, 'USD', '1234567'),
    ('SK502', 'DXB', 'CMB', '09:30', 270, 'A350', 300.00, 'USD', '1234567'),
    ('SK001', 'DXB', 'LHR', '08:30', 470, 'A380', 540.00, 'USD', '1234567'),
    ('SK002', 'LHR', 'DXB', '14:00', 410, 'A380', 520.00, 'USD', '1234567'),
    ('SK201', 'BOM', 'DXB', '03:00', 190, 'B777', 180.00, 'USD', '1234567'),
    ('SK203', 'BOM', 'DWC', '04:30', 190, 'B777', 175.00, 'USD', '1234567'),
    ('SK431', 'DXB', 'SIN', '10:00', 440, 'B777', 410.00, 'USD', '1234567'),
    ('SK901', 'DWC', 'JFK', '10:30', 840, 'B777', 930.00, 'USD', '1234567'),
    ('SK902', 'JFK', 'DWC', '22:00', 760, 'B777', 910.00, 'USD', '1234567'),
    ('SK411', 'DWC', 'SYD', '11:00', 840, 'A380', 880.00, 'USD', '135');
