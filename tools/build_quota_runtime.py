"""Build and bundle the quota filesystem helper; players need no FUSE userspace install."""
from pathlib import Path
import re
import shutil
import subprocess
root=Path(__file__).resolve().parents[1]
output=root/'build/generated/quotaResources/retrocomputers/native/quota'
output.mkdir(parents=True,exist_ok=True)
subprocess.run(['gcc','-O2','-Wall','-D_FILE_OFFSET_BITS=64',str(root/'src/main/c/quota_fs.c'),'-o',str(output/'quota-runner'),'-lfuse3','-Wl,-rpath,$ORIGIN'],check=True)
ldd=subprocess.check_output(['ldd',str(output/'quota-runner')],text=True)
match=re.search(r'(libfuse3\.so\.\d+) => (\S+)',ldd)
if not match:raise RuntimeError('libfuse3 dependency not found')
library=Path('/usr/lib')/match[1]
shutil.copy2((library if library.exists() else Path(match[2])).resolve(),output/match[1])
shutil.copyfile('/usr/bin/fusermount3',output/'fusermount3')
(output/'fusermount3').chmod(0o755)
(output/'files.txt').write_text('quota-runner\n'+match[1]+'\nfusermount3\n')
license=Path('/usr/share/licenses/fuse3')
if license.exists():shutil.copytree(license,output/'licenses',dirs_exist_ok=True)
print('Quota filesystem helper ready')
