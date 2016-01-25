from __future__ import absolute_import
from __future__ import division
from __future__ import print_function
from __future__ import unicode_literals


"""
Fetches project dependencies. Run this script once from project's root directory
before first `buck build`.
"""


import json
import os.path
import subprocess


def fetch_maven_library(name):
    subprocess.check_call(['buck', 'fetch', 'deps:{}'.format(name)])


def main():
    subprocess.check_call(['buck', 'build', 'deps:list-deps'])
    deps_json_path = os.path.join(
        'buck-out',
        'gen',
        'deps',
        'list-deps',
        'dependencies.json'
    )

    with open(deps_json_path) as f:
        deps = json.load(f)

    for (name, dep) in deps.iteritems():
        fetch_maven_library(name)


if __name__ == '__main__':
    main()
