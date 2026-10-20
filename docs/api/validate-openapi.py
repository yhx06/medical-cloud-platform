#!/usr/bin/env python3
"""Validate docs/api/course-openapi.yaml against the actual JAX-RS routes.

Usage: python validate-openapi.py
Exit 0 when the spec exactly covers every public route/method (no missing, no extra).
"""
import re, glob, json, sys, os

# Run from the worktree root so the course/ source tree is resolvable.
ROOT = os.getcwd()
OPENAPI = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'course-openapi.yaml')


def extract_source_routes():
    routes = {}
    files = sorted(glob.glob(os.path.join(ROOT, 'course/paas/src/main/java/com/medcloud/course/paas/api/*Resource.java')))
    for f in files:
        s = open(f, encoding='utf-8').read()
        cm = re.search(r'@Path\("([^"]*)"\)[\s\S]{0,300}?public class\s+(\w+)', s)
        base = cm.group(1).strip('/') if cm else ''
        for m in re.finditer(r'@(GET|POST|PUT|DELETE|PATCH)\b[\s\S]*?public\s+[\w.<>,\s]+?\s+(\w+)\s*\(', s):
            verb = m.group(1).lower()
            block = s[m.start():m.end()]
            pm = re.search(r'@Path\("([^"]*)"\)', block)
            sub = pm.group(1).strip('/') if pm else ''
            path = '/' + '/'.join(x for x in [base, sub] if x)
            routes[(verb, path)] = True
    return routes


def extract_openapi_paths():
    spec = open(OPENAPI, encoding='utf-8').read()
    paths = {}
    # Parse the paths: top-level keys indented two spaces under "paths:", with an HTTP verb child.
    cur = None
    for line in spec.splitlines():
        if (line.startswith('  /') or line.startswith('  "')) and line.rstrip().endswith(':'):
            cur = line.strip().rstrip(':').strip('"').strip("'")
        elif cur is not None and re.match(r'    (get|post|put|delete|patch):', line):
            paths[(line.strip().rstrip(':'), cur)] = True
            cur = None
    return paths


def main():
    src = extract_source_routes()
    spec = extract_openapi_paths()
    missing = sorted(k for k in src if k not in spec)
    extra = sorted(k for k in spec if k not in src)
    print(f'source routes: {len(src)}, openapi paths: {len(spec)}')
    for m, p in missing:
        print(f'MISSING in OpenAPI: {m.upper()} {p}')
    for m, p in extra:
        print(f'EXTRA in OpenAPI: {m.upper()} {p}')
    if missing or extra:
        print('VALIDATION FAILED')
        return 1
    print('VALIDATION OK: OpenAPI exactly matches source routes')
    return 0


if __name__ == '__main__':
    sys.exit(main())
