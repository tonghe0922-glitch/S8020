from pathlib import Path
import json
import re

root = Path(__file__).resolve().parents[2]
web = root / 'technical-platform' / 'web'
required = [web / 'work.html', web / 'tech.html', web / 'src/portals/work/main.ts', web / 'src/portals/tech/main.ts']
forbidden = [web / 'employee.html', web / 'center.html', web / 'admin.html', web / 'src/portals/employee', web / 'src/portals/center', web / 'src/portals/admin']
missing = [str(path.relative_to(root)) for path in required if not path.exists()]
present = [str(path.relative_to(root)) for path in forbidden if path.exists()]
if missing or present:
    raise SystemExit(f'missing={missing}; forbidden={present}')

package = json.loads((web / 'package.json').read_text(encoding='utf-8'))
scripts = package['scripts']
for key in ('dev:work', 'dev:tech', 'build:work', 'build:tech', 'quality:ports'):
    if key not in scripts:
        raise SystemExit(f'Missing script: {key}')
if any('admin' in key or 'employee' in key or 'center' in key for key in scripts):
    raise SystemExit('Retired runtime script remains')

portal_config = (web / 'src/platform/portal-config.ts').read_text(encoding='utf-8')
if "'work' | 'tech'" not in portal_config:
    raise SystemExit('Runtime ports are not exactly work and tech')

navigation = (web / 'src/router/navigation-catalog.ts').read_text(encoding='utf-8')
expected = ['我的工作台','中心事务','企业通讯录','待办与任务','审批与申请','通知与制度','例会与会议','学习与成行','演出节目单','报销与经费','个人综合服务','站内通信']
positions = [navigation.find(f"label: '{label}'") for label in expected]
if any(position < 0 for position in positions) or positions != sorted(positions):
    raise SystemExit('Work navigation order is invalid')

forbidden_roots = ['Knowledge Base', 'Construction Master Schedule.csv', 'docs/implementation', '.tmp', '.ci']
remaining = [path for path in forbidden_roots if (root / path).exists()]
if remaining:
    raise SystemExit(f'Legacy roots remain: {remaining}')
print('Two-port repository guard passed.')
