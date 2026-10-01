"""Стартовый словарь. Неизвестные единицы требуют настройки перед вводом."""

PARAMS = [
    ('production','Производительность','','т/ч','measurement','base','Режим и качество'),
    ('pal','Расход ПАЛ','','','measurement','scales','Режим и качество'),
    ('propylene','Расход пропилена','','','measurement','scales','Режим и качество'),
    ('mfi','ПТР','','','lab','independent','Режим и качество'),
    ('ethylene','Свежий этилен','FIR11004','','measurement','base','Свежий этилен'),
    ('drive_current','Ток главного привода','EIE-A404','А','measurement','independent','Экструзия'),
    ('water_temperature','Температура горячей воды','','°C','measurement','independent','Горячая вода'),
    ('t14069','Температура T14069','T14069','°C','measurement','independent','Реактор'),
] + [
    (f'{prefix}{z}',f'{label} · зона {z}','','°C' if prefix=='peak' else '',role,dependency,group)
    for z in (1,2,3)
    for prefix,label,role,dependency,group in [
        ('initiator','Инициатор, измеренный расход','measurement','scales','Инициатор по зонам'),
        ('suutp_flow','Инициатор, расчёт СУУТП','suutp','scales','Инициатор по зонам'),
        ('peak','Максимальная температура','measurement','independent','Реактор'),
    ]
]
