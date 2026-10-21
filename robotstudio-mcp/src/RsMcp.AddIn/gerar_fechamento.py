"""Gerador de fechamento de seguranca do RsMcp.

Roda o gerador CadQuery do skill "fechamento-seguranca-celula" do usuario (codigo da secao do gerador
no SKILL.md instalado no PC) com a configuracao de um JSON. O codigo do skill nao e copiado: e lido do
SKILL.md a cada execucao, entao uma atualizacao do skill vale na hora.

Uso:  python gerar_fechamento.py config.json
config.json: NOME e LINHAS (obrigatorios), H, Z0, MAXP, DOOR_ANG e OUT (opcionais), no formato do
skill, com listas no lugar de tuplas e null para o vao "resto". Sem OUT, o STEP sai nesta pasta.
"""
import glob
import json
import os
import re
import sys

SKILL = "fechamento-seguranca-celula"
HERE = os.path.dirname(os.path.abspath(__file__))
CONFIG_KEYS = ("NOME", "H", "Z0", "MAXP", "DOOR_ANG", "LINHAS")


def find_skill():
    """SKILL.md: RSMCP_FENCE_SKILL; skills do Claude Code (inclusive sincronizadas); Documents\\RsMcp\\skills."""
    custom = os.environ.get("RSMCP_FENCE_SKILL")
    if custom:
        return custom
    skills = os.path.join(os.path.expanduser("~"), ".claude", "skills")
    patterns = [
        os.path.join(skills, SKILL, "SKILL.md"),
        os.path.join(skills, "**", SKILL, "SKILL.md"),
        os.path.join(HERE, "..", "skills", SKILL, "SKILL.md"),
    ]
    for pattern in patterns:
        found = sorted(glob.glob(pattern, recursive=True), key=os.path.getmtime, reverse=True)
        if found:
            return found[0]
    sys.exit("ERRO: skill %s nao encontrado. Instale o SKILL.md em %s" % (SKILL, patterns[0]))


def generator_code(skill_path):
    """O bloco python do skill que define build() e check()."""
    with open(skill_path, encoding="utf-8") as f:
        text = f.read()
    for block in re.findall(r"```python\n(.*?)```", text, re.S):
        if "def build(" in block and "def check(" in block:
            return block
    sys.exit("ERRO: codigo do gerador (def build / def check) nao encontrado em " + skill_path)


def main():
    if len(sys.argv) != 2:
        sys.exit("uso: python gerar_fechamento.py config.json")
    with open(sys.argv[1], encoding="utf-8") as f:
        cfg = json.load(f)
    missing = [k for k in ("NOME", "LINHAS") if k not in cfg]
    if missing:
        sys.exit("ERRO: faltam no config: " + ", ".join(missing))
    out = os.path.abspath(cfg.get("OUT") or os.path.join(HERE, cfg["NOME"] + ".step"))

    code = generator_code(find_skill())
    # Os valores do JSON entram logo depois do bloco CONFIG do skill; o resto do gerador roda igual.
    config_block = re.search(r"^# =+ CONFIG =+\n.*?^# =+\n", code, re.S | re.M)
    if not config_block:
        sys.exit("ERRO: bloco CONFIG nao encontrado no gerador do skill.")
    overrides = "".join("%s = __cfg__[%r]\n" % (k, k) for k in CONFIG_KEYS if k in cfg) + "OUT = __out__\n"
    code = code[:config_block.end()] + overrides + code[config_block.end():]

    os.makedirs(os.path.dirname(out), exist_ok=True)
    exec(compile(code, "gerador_fechamento", "exec"), {"__name__": "__main__", "__cfg__": cfg, "__out__": out})


if __name__ == "__main__":
    main()
