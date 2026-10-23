"""Visualizador de backups de CLP Allen-Bradley (.ACD / .L5X).

Uso:
    python acd_viewer.py PROJETO.ACD            -> gera PROJETO.html
    python acd_viewer.py PROJETO.L5X -o saida.html
    python acd_viewer.py PROJETO.ACD --json     -> gera também PROJETO.json

O .ACD é convertido para L5X com a biblioteca acd-tools; o L5X é reduzido a um
JSON com o necessário para visualização e embutido em um HTML único, que abre
em qualquer navegador sem instalar nada.
"""
import argparse
import json
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

HERE = Path(__file__).resolve().parent
TEMPLATE = HERE / "viewer.html"
PLACEHOLDER = "window.__PLC_DATA__ = null;"


def acd_to_l5x(acd_path: Path, l5x_path: Path) -> None:
    try:
        from acd.api import ConvertAcdToL5x
    except ImportError:
        sys.exit("Biblioteca acd-tools não instalada: pip install -r requirements.txt")
    ConvertAcdToL5x(str(acd_path), str(l5x_path)).extract()


def text(el, tag=None):
    """Texto de um elemento (ou filho), aceitando o formato com LocalizedComment."""
    if tag is not None:
        el = el.find(tag) if el is not None else None
    if el is None:
        return ""
    value = el.text or ""
    if not value.strip():
        child = el.find("*")
        value = (child.text or "") if child is not None else ""
    return value.strip()


def children(el, tag):
    found = el.find(tag) if el is not None else None
    return list(found) if found is not None else []


def tag_info(t):
    info = {"name": t.get("Name"), "type": t.get("DataType") or ""}
    if t.get("Dimensions"):
        info["dim"] = t.get("Dimensions")
    if t.get("AliasFor"):
        info["alias"] = t.get("AliasFor")
    if t.get("Usage"):
        info["usage"] = t.get("Usage")
    desc = text(t, "Description")
    if desc:
        info["desc"] = desc
    return info


def routine_info(r):
    info = {"name": r.get("Name"), "type": r.get("Type")}
    desc = text(r, "Description")
    if desc:
        info["desc"] = desc
    if r.get("Type") == "RLL":
        info["rungs"] = [
            {"n": int(rg.get("Number", i)), "t": text(rg, "Text"), "c": text(rg, "Comment")}
            for i, rg in enumerate(r.iter("Rung"))
        ]
    elif r.get("Type") == "ST":
        lines = [ln.text or "" for ln in r.iter("Line")]
        if lines:
            info["lines"] = lines
    return info


def l5x_to_data(l5x_path: Path) -> dict:
    root = ET.parse(l5x_path).getroot()
    c = root.find("Controller")
    data = {
        "controller": {
            "name": c.get("Name"),
            "processor": c.get("ProcessorType"),
            "revision": f"{c.get('MajorRev')}.{c.get('MinorRev')}",
            "software": root.get("SoftwareRevision"),
            "desc": text(c, "Description"),
        },
        "tasks": [
            {
                "name": t.get("Name"),
                "type": t.get("Type"),
                "rate": t.get("Rate"),
                "priority": t.get("Priority"),
                "programs": [s.get("Name") for s in t.iter("ScheduledProgram")],
            }
            for t in children(c, "Tasks")
        ],
        "programs": [
            {
                "name": p.get("Name"),
                "main": p.get("MainRoutineName"),
                "desc": text(p, "Description"),
                "tags": [tag_info(t) for t in children(p, "Tags")],
                "routines": sorted(
                    (routine_info(r) for r in children(p, "Routines")),
                    key=lambda r: r["name"].lower(),
                ),
            }
            for p in children(c, "Programs")
        ],
        "aois": [
            {
                "name": a.get("Name"),
                "revision": a.get("Revision"),
                "desc": text(a, "Description"),
                "params": [tag_info(t) for t in children(a, "Parameters")],
                "tags": [tag_info(t) for t in children(a, "LocalTags")],
                "routines": [routine_info(r) for r in children(a, "Routines")],
            }
            for a in children(c, "AddOnInstructionDefinitions")
        ],
        "datatypes": [
            {
                "name": d.get("Name"),
                "desc": text(d, "Description"),
                "members": [
                    tag_info(m) | ({"dim": m.get("Dimension")} if m.get("Dimension") not in (None, "0") else {})
                    for m in children(d, "Members")
                    if m.get("Hidden") != "true"
                ],
            }
            for d in children(c, "DataTypes")
        ],
        "modules": [
            {
                "name": m.get("Name") or "",
                "catalog": m.get("CatalogNumber"),
                "parent": m.get("ParentModule"),
                "address": ", ".join(p.get("Address") for p in m.iter("Port") if p.get("Address")),
                "inhibited": m.get("Inhibited") == "true",
                "desc": text(m, "Description"),
            }
            for m in children(c, "Modules")
        ],
        "tags": [tag_info(t) for t in children(c, "Tags")],
    }
    data["programs"].sort(key=lambda p: p["name"].lower())
    for key in ("aois", "datatypes", "tags"):
        data[key].sort(key=lambda x: x["name"].lower())
    return data


def build_html(data: dict, out_path: Path) -> None:
    template = TEMPLATE.read_text(encoding="utf-8")
    payload = json.dumps(data, ensure_ascii=False, separators=(",", ":")).replace("</", "<\\/")
    out_path.write_text(template.replace(PLACEHOLDER, f"window.__PLC_DATA__ = {payload};"), encoding="utf-8")


def main():
    ap = argparse.ArgumentParser(description="Gera um visualizador HTML para backup .ACD/.L5X")
    ap.add_argument("arquivo", type=Path, help="arquivo .ACD ou .L5X")
    ap.add_argument("-o", "--saida", type=Path, help="HTML de saída (padrão: mesmo nome .html)")
    ap.add_argument("--json", action="store_true", help="salvar também o JSON extraído")
    args = ap.parse_args()

    src = args.arquivo
    out = args.saida or src.with_suffix(".html")
    with tempfile.TemporaryDirectory() as tmp:
        if src.suffix.lower() == ".acd":
            l5x = Path(tmp) / (src.stem + ".L5X")
            print(f"Convertendo {src.name} para L5X...")
            acd_to_l5x(src, l5x)
        else:
            l5x = src
        data = l5x_to_data(l5x)

    if args.json:
        src.with_suffix(".json").write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
    build_html(data, out)
    rungs = sum(len(r.get("rungs", [])) for p in data["programs"] for r in p["routines"])
    print(
        f"{data['controller']['name']}: {len(data['programs'])} programas, {rungs} rungs, "
        f"{len(data['tags'])} tags de controller, {len(data['modules'])} módulos"
    )
    print(f"Visualizador gerado: {out}")


if __name__ == "__main__":
    main()
