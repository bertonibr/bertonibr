"""Monta o APK (sem assinatura) do Treino de Contas sem precisar do Android SDK.

Gera o AndroidManifest.xml binário e o resources.arsc (só o ícone) à mão
e junta tudo com o classes.dex e os assets num zip alinhado.

Uso: python3 build_apk.py <classes.dex> <pasta_assets> <icone.png> <saida.apk>
"""
import struct
import sys
import zipfile

PACKAGE = "br.bertoni.contas"
LABEL = "Matemática"
VERSION_CODE = 5
VERSION_NAME = "1.4"
MIN_SDK, TARGET_SDK = 24, 34

ANDROID_NS = "http://schemas.android.com/apk/res/android"
ICON_ID = 0x7F010000                 # mipmap/ic_launcher
THEME_ID = 16974121                  # @android:style/Theme.DeviceDefault.NoActionBar

# IDs dos atributos android:* (android.R.attr)
ATTR = {
    "theme": 16842752, "label": 16842753, "icon": 16842754, "name": 16842755,
    "exported": 16842768, "screenOrientation": 16842782, "configChanges": 16842783,
    "minSdkVersion": 16843276, "versionCode": 16843291, "versionName": 16843292,
    "targetSdkVersion": 16843376, "windowSoftInputMode": 16843307,
}

T_REF, T_STRING, T_INT, T_HEX, T_BOOL = 0x01, 0x03, 0x10, 0x11, 0x12


def pad4(b):
    return b + b"\0" * (-len(b) % 4)


def string_pool(strings):
    offsets, data = [], b""
    for s in strings:
        offsets.append(len(data))
        u = s.encode("utf-16-le")
        data += struct.pack("<H", len(s)) + u + b"\0\0"
    data = pad4(data)
    header_size = 28
    strings_start = header_size + 4 * len(strings)
    body = b"".join(struct.pack("<I", o) for o in offsets) + data
    return struct.pack("<HHIIIIII", 0x0001, header_size, header_size + len(body),
                       len(strings), 0, 0, strings_start, 0) + body


# ---------- AndroidManifest.xml binário ----------

def manifest():
    # (tag, [(attr, tipo, valor)], filhos)
    activity = ("activity", [
        ("name", T_STRING, PACKAGE + ".MainActivity"),
        ("exported", T_BOOL, True),
        ("screenOrientation", T_INT, 1),           # retrato
        ("configChanges", T_HEX, 0x04A0),           # keyboardHidden|orientation|screenSize
        ("windowSoftInputMode", T_HEX, 0x14),       # teclado aberto, tela se ajusta
    ], [("intent-filter", [], [
        ("action", [("name", T_STRING, "android.intent.action.MAIN")], []),
        ("category", [("name", T_STRING, "android.intent.category.LAUNCHER")], []),
    ])])
    root = ("manifest", [
        ("versionCode", T_INT, VERSION_CODE),
        ("versionName", T_STRING, VERSION_NAME),
        ("package", T_STRING, PACKAGE),
    ], [
        ("uses-sdk", [("minSdkVersion", T_INT, MIN_SDK), ("targetSdkVersion", T_INT, TARGET_SDK)], []),
        ("application", [
            ("theme", T_REF, THEME_ID),
            ("label", T_STRING, LABEL),
            ("icon", T_REF, ICON_ID),
        ], [activity]),
    ])

    # Nomes de atributos android:* primeiro, na ordem do mapa de recursos.
    attr_names = sorted({a for a in ATTR}, key=lambda n: ATTR[n])
    strings = list(attr_names)

    def idx(s):
        if s not in strings:
            strings.append(s)
        return strings.index(s)

    for s in ("android", ANDROID_NS):
        idx(s)

    def collect(node):
        tag, attrs, kids = node
        idx(tag)
        for name, typ, val in attrs:
            idx(name)
            if typ == T_STRING:
                idx(val)
        for k in kids:
            collect(k)
    collect(root)

    def node_header(t, size):
        return struct.pack("<HHIII", t, 16, size, 1, 0xFFFFFFFF)

    chunks = []
    ns_ext = struct.pack("<II", idx("android"), idx(ANDROID_NS))
    chunks.append(node_header(0x0100, 16 + len(ns_ext)) + ns_ext)

    def emit(node):
        tag, attrs, kids = node
        # Atributos ordenados pelo ID do recurso; "package" (sem ID) vem por último.
        attrs = sorted(attrs, key=lambda a: ATTR.get(a[0], 0xFFFFFFFF))
        body = b""
        for name, typ, val in attrs:
            ns = idx(ANDROID_NS) if name in ATTR else 0xFFFFFFFF
            if typ == T_STRING:
                raw, data = idx(val), idx(val)
            elif typ == T_BOOL:
                raw, data = 0xFFFFFFFF, 0xFFFFFFFF if val else 0
            else:
                raw, data = 0xFFFFFFFF, val
            body += struct.pack("<IIIHBBI", ns, idx(name), raw, 8, 0, typ, data)
        ext = struct.pack("<IIHHHHHH", 0xFFFFFFFF, idx(tag), 20, 20, len(attrs), 0, 0, 0) + body
        chunks.append(node_header(0x0102, 16 + len(ext)) + ext)
        for k in kids:
            emit(k)
        end = struct.pack("<II", 0xFFFFFFFF, idx(tag))
        chunks.append(node_header(0x0103, 16 + len(end)) + end)
    emit(root)
    chunks.append(node_header(0x0101, 16 + len(ns_ext)) + ns_ext)

    pool = string_pool(strings)
    ids = b"".join(struct.pack("<I", ATTR[n]) for n in attr_names)
    resmap = struct.pack("<HHI", 0x0180, 8, 8 + len(ids)) + ids
    body = pool + resmap + b"".join(chunks)
    return struct.pack("<HHI", 0x0003, 8, 8 + len(body)) + body


# ---------- resources.arsc com um único recurso: mipmap/ic_launcher ----------

ICON_PATH = "res/mipmap/ic_launcher.png"


def resources():
    values = string_pool([ICON_PATH])
    type_strings = string_pool(["mipmap"])
    key_strings = string_pool(["ic_launcher"])

    spec = struct.pack("<HHIBBHI", 0x0202, 16, 16 + 4, 1, 0, 0, 1) + struct.pack("<I", 0)

    config = struct.pack("<I", 64) + b"\0" * 60          # configuração padrão
    type_header_size = 20 + len(config)
    entries = struct.pack("<HHI", 8, 0, 0) + struct.pack("<HBBI", 8, 0, T_STRING, 0)
    offsets = struct.pack("<I", 0)
    entries_start = type_header_size + len(offsets)
    type_chunk = struct.pack("<HHIBBHII", 0x0201, type_header_size,
                             entries_start + len(entries), 1, 0, 0, 1, entries_start)
    type_chunk += config + offsets + entries

    pkg_header_size = 288
    name = PACKAGE.encode("utf-16-le").ljust(256, b"\0")
    type_off = pkg_header_size
    key_off = type_off + len(type_strings)
    pkg_body = type_strings + key_strings + spec + type_chunk
    pkg = struct.pack("<HHII", 0x0200, pkg_header_size, pkg_header_size + len(pkg_body), 0x7F)
    pkg += name + struct.pack("<IIIII", type_off, 1, key_off, 1, 0) + pkg_body

    body = values + pkg
    return struct.pack("<HHII", 0x0002, 12, 12 + len(body), 1) + body


# ---------- zip ----------

def write_aligned(z, name, data, compress):
    info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED if compress else zipfile.ZIP_STORED
    if not compress:
        # Arquivos sem compressão precisam começar em endereço múltiplo de 4.
        start = z.fp.tell() + 30 + len(name.encode())
        info.extra = b"\0" * (-start % 4)
    z.writestr(info, data)


def main(dex, assets_dir, icon, out):
    import os
    with zipfile.ZipFile(out, "w") as z:
        write_aligned(z, "AndroidManifest.xml", manifest(), True)
        write_aligned(z, "resources.arsc", resources(), False)
        write_aligned(z, "classes.dex", open(dex, "rb").read(), True)
        write_aligned(z, ICON_PATH, open(icon, "rb").read(), False)
        for root, _, files in sorted(os.walk(assets_dir)):
            for f in sorted(files):
                path = os.path.join(root, f)
                rel = os.path.relpath(path, assets_dir).replace(os.sep, "/")
                write_aligned(z, "assets/" + rel, open(path, "rb").read(), not f.endswith(".woff2"))


if __name__ == "__main__":
    main(*sys.argv[1:5])
