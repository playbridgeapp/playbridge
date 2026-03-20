import os
import re

def parse_annotations(file_path):
    with open(file_path, "r") as f:
        lines = f.readlines()

    annotations = {}
    in_tree = False
    for line in lines:
        if "Package Structure" in line:
            in_tree = True
        if in_tree and line.startswith("```"):
            if "com.playbridge.sender/" not in line and not line.strip() == "```":
                continue # wait for the end
            if "com.playbridge.sender/" not in line:
                 in_tree = False
        if in_tree:
            match = re.search(r'├──\s+([^ ]+)\s+(.+)', line.strip())
            if match:
                file_name = match.group(1).strip()
                ann = match.group(2).strip()
                if ann.startswith("(") or ann.startswith("~") or ann.startswith("#"):
                    annotations[file_name] = ann
            match2 = re.search(r'│\s+├──\s+([^ ]+)\s+(.+)', line)
            if match2:
                file_name = match2.group(1).strip()
                ann = match2.group(2).strip()
                if ann.startswith("(") or ann.startswith("~") or ann.startswith("#"):
                    annotations[file_name] = ann
            match3 = re.search(r'│\s+└──\s+([^ ]+)\s+(.+)', line)
            if match3:
                file_name = match3.group(1).strip()
                ann = match3.group(2).strip()
                if ann.startswith("(") or ann.startswith("~") or ann.startswith("#"):
                    annotations[file_name] = ann
    return annotations

def generate_tree(base_path, depth, prefix, annotations):
    tree_str = ""
    try:
        items = sorted(os.listdir(base_path))
    except FileNotFoundError:
        return ""

    dirs = [i for i in items if os.path.isdir(os.path.join(base_path, i)) and not i.startswith('.')]
    files = [i for i in items if os.path.isfile(os.path.join(base_path, i)) and not i.startswith('.')]

    for i, d in enumerate(dirs):
        is_last = (i == len(dirs) - 1 and len(files) == 0)
        connector = "└── " if is_last else "├── "
        ann = annotations.get(d, "")
        if ann:
            pad = max(1, 28 - len(d))
            tree_str += f"{prefix}{connector}{d}/{" " * pad}{ann}\n"
        else:
            tree_str += f"{prefix}{connector}{d}/\n"

        new_prefix = prefix + ("    " if is_last else "│   ")
        tree_str += generate_tree(os.path.join(base_path, d), depth + 1, new_prefix, annotations)

    for i, f in enumerate(files):
        if not f.endswith('.kt') and not f.endswith('.xml'):
            continue
        is_last = (i == len(files) - 1)
        connector = "└── " if is_last else "├── "

        ann = annotations.get(f, "")
        if ann:
            pad = max(1, 28 - len(f))
            tree_str += f"{prefix}{connector}{f}{' ' * pad}{ann}\n"
        else:
            tree_str += f"{prefix}{connector}{f}\n"

    return tree_str

def update_phone_md():
    arch_file = "phone/ARCHITECTURE.md"
    annotations = parse_annotations(arch_file)
    with open(arch_file, "r") as f:
        content = f.read()

    phone_base = "phone/app/src/main/java/com/playbridge/sender"
    phone_tree = generate_tree(phone_base, 0, "", annotations)

    new_struct = "## Package Structure\n```\ncom.playbridge.sender/\n" + phone_tree.rstrip() + "\n```"

    start_idx = content.find("## Package Structure")
    end_idx = content.find("## Key Components", start_idx)

    if start_idx != -1 and end_idx != -1:
        new_content = content[:start_idx] + new_struct + "\n\n" + content[end_idx:]
        with open(arch_file, "w") as f:
            f.write(new_content)
        print("Updated phone/ARCHITECTURE.md successfully.")
    else:
        print("Could not find section boundaries.")

if __name__ == "__main__":
    update_phone_md()
