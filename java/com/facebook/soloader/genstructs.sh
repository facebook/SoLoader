#!/bin/bash
# Copyright (c) Facebook, Inc. and its affiliates.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

#
# This script generates Java structures that contain the offsets of
# fields in various ELF ABI structures.  com.facebook.soloader.MinElf
# uses these structures while parsing ELF files.
#

set -euo pipefail

struct2java() {
    ../../../../scripts/struct2java.py "$@"
}

declare -a structs=(Elf32_Ehdr Elf64_Ehdr)
structs+=(Elf32_Ehdr Elf64_Ehdr)
structs+=(Elf32_Phdr Elf64_Phdr)
structs+=(Elf32_Shdr Elf64_Shdr)
structs+=(Elf32_Dyn Elf64_Dyn)

for struct in "${structs[@]}"; do
    cat > elfhdr.c <<EOF
#include <elf.h>
static const $struct a;
EOF
    gcc -g -c -o elfhdr.o elfhdr.c
    cat > $struct.java <<EOF
/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// AUTOMATICALLY GENERATED CODE. Regenerate with genstructs.sh.
package com.facebook.soloader;
EOF
    struct2java elfhdr.o $struct >> $struct.java
done

rm -f elfhdr.o elfhdr.c
