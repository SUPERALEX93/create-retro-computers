"""Create-style passive instruments, sharing the original 32px gauge atlas."""
from pathlib import Path
import json
import zipfile
import copy
import sys

ROOT = Path(__file__).resolve().parents[1] / 'src/main/resources'
CREATE = Path(sys.argv[1]) if len(sys.argv) > 1 else Path.home() / '.local/share/PrismLauncher/instances/1.21.1/minecraft/mods/create-1.21.1-6.0.10.jar'
NAMES = ('tilt_sensor', 'motion_speedometer', 'altimeter')

def save(path, data):
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(data, indent=2, ensure_ascii=False) + '\n')

def turn(element):
    """Native east-facing gauge -> south-facing gauge, also rotate the face UVs."""
    e = copy.deepcopy(element)
    a, b = e['from'], e['to']
    e['from'], e['to'] = [16-b[2], a[1], a[0]], [16-a[2], b[1], b[0]]
    e.pop('rotation', None)
    mapping = dict(east='south', south='west', west='north', north='east', up='up', down='down')
    e['faces'] = {mapping[k]: v for k, v in e['faces'].items()}
    for face in ('up','down'):
        if face in e['faces']:
            f = e['faces'][face]
            f['rotation'] = (f.get('rotation', 0) + (90 if face == 'up' else 270)) % 360
    return e

def cuboid(a, b, texture, uv=(0,0,16,16)):
    return dict(**{'from':a,'to':b}, faces={face:{'texture':texture,'uv':list(uv)} for face in ('north','south','east','west','up','down')})

with zipfile.ZipFile(CREATE) as z:
    base = json.loads(z.read('assets/create/models/block/gauge/base.json'))
    head = json.loads(z.read('assets/create/models/block/gauge/speedometer/head.json'))
base['elements'][0]['to'][0] = 14
# The north/south UVs of Create's shaft gauge depict open shaft sockets.
# Passive sensors use the closed metal panel on those sides as well.
for face in ('north', 'south'):
    base['elements'][0]['faces'][face]['uv'] = [9, 10, 16, 16]
# Unlike Create's shaft gauge these instruments have a closed rear casing.
for name, color in zip(NAMES, ('cyan','orange','lime')):
    elements = [turn(e) for e in base['elements'] + head['elements']]
    button = elements[3]
    for f in button['faces'].values(): f['texture'] = '#lamp'; f['uv'] = [0,0,16,16]
    # Distinct markings: cross-level, acceleration ticks, and height ladder.
    if name == 'tilt_sensor':
        elements.append(cuboid([4.8,7.8,16.09],[11.2,8.2,16.11],'#lamp'))
    elif name == 'altimeter':
        for y in (5,7,9,11): elements.append(cuboid([5,y,16.09],[6.5,y+.3,16.11],'#lamp'))
    else:
        for x,y in ((5.0,10),(7.8,11),(10.6,10)):
            elements.append(cuboid([x,y,16.09],[x+.4,y+.7,16.11],'#lamp'))
    model = {'parent':'minecraft:block/block', 'textures':{'2':'create:block/gauge','particle':'create:block/gauge',
        'lamp':f'minecraft:block/{color}_concrete'},'elements':elements}
    save(f'assets/retrocomputers/models/block/{name}.json', model)
    save(f'assets/retrocomputers/blockstates/{name}.json', {'variants':{f'facing={face}':{'model':f'retrocomputers:block/{name}', 'y':angle} for face,angle in [('south',0),('west',90),('north',180),('east',270)]}})
    item = copy.deepcopy(model)
    item['display'] = {'gui':{'rotation':[30,315,0],'translation':[0,0,0],'scale':[.625,.625,.625]},
        'firstperson_righthand':{'rotation':[0,45,0],'translation':[0,0,0],'scale':[.4,.4,.4]},
        'thirdperson_righthand':{'rotation':[75,45,0],'translation':[0,2.5,0],'scale':[.375,.375,.375]},
        'fixed':{'rotation':[0,180,0],'scale':[.5,.5,.5]}}
    item['elements'].append(cuboid([7.65,7.65,16.13],[8.35,11.3,16.35],'#2',[0,9.5,.5,10]))
    save(f'assets/retrocomputers/models/item/{name}.json',item)
    save(f'assets/retrocomputers/models/item/incomplete_{name}.json',{'parent':'retrocomputers:item/incomplete_interface_module'})
    save(f'data/retrocomputers/loot_table/blocks/{name}.json', {'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':f'retrocomputers:{name}'}],'conditions':[{'condition':'minecraft:survives_explosion'}]}]})
needle={'parent':'minecraft:block/block','textures':{'2':'create:block/gauge'},'elements':[
    cuboid([7.65,7.65,16.13],[8.35,11.3,16.35],'#2',[0,9.5,.5,10]),
    cuboid([7.3,7.3,16.15],[8.7,8.7,16.5],'#2',[13,5,14,6])]}
save('assets/retrocomputers/models/block/sensor_needle.json',needle)
for tag in ['data/minecraft/tags/block/mineable/pickaxe.json','data/sable/tags/block/silent_assembly_removal.json']:
    data=json.loads((ROOT/tag).read_text())
    for name in NAMES:
        id=f'retrocomputers:{name}'
        if id not in data['values']: data['values'].append(id)
    save(tag,data)
