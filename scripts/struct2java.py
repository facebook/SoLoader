#!/usr/bin/env python3
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

# This script outputs a Java class file giving the offsets of fields
# in a C struct.

from __future__ import absolute_import
from __future__ import division
from __future__ import print_function
from __future__ import unicode_literals
import sys
import argparse
from elftools.elf.elffile import ELFFile

parser = argparse.ArgumentParser(description='Output struct layout')
parser.add_argument('elffile', help='ELF file containing debug information')
parser.add_argument('struct', help='Name of struct (or typedef of struct)')
args = parser.parse_args()

def die(fmt, *args):
  sys.stderr.write(('struct2java.py: ' + fmt + '\n') % args)
  sys.exit(1)

def DIE_to_name(DIE):
  name_attr = DIE.attributes.get('DW_AT_name')
  if name_attr is not None:
    return name_attr.value.decode('ascii')

  return None

with open(args.elffile, 'rb') as f:
  elffile = ELFFile(f)
  if not elffile.has_dwarf_info():
    die('file does not contain debug information')

  dwarfinfo = elffile.get_dwarf_info()

  structs_by_offset = {}
  structs_by_name = {}
  typedefs_by_name = {}

  for CU in dwarfinfo.iter_CUs():
    for DIE in CU.iter_DIEs():
      if DIE.tag == 'DW_TAG_typedef':
        name = DIE_to_name(DIE)
        if name is not None:
          typedefs_by_name[name] = DIE
      
      if DIE.tag == 'DW_TAG_structure_type':
        structs_by_offset[DIE.offset] = DIE
        name = DIE_to_name(DIE)
        if name is not None:
          structs_by_name[name] = DIE

  struct = structs_by_name.get(args.struct)
  
  if struct is None:
    td = typedefs_by_name.get(args.struct)
    if td is not None:
      struct_offset = td.attributes['DW_AT_type'].value + td.cu.cu_offset
      struct = structs_by_offset.get(struct_offset)

  if struct is None:
    die('could not find struct %s', args.struct)

  print('final class %s {' % args.struct)
  for child_DIE in struct.iter_children():
    if child_DIE.tag == 'DW_TAG_member':
      name = DIE_to_name(child_DIE)
      offset = child_DIE.attributes['DW_AT_data_member_location'].value
      print('  public static final int %s = 0x%x;' % (name, offset))
    else:
      die('unknown child of struct DIE: %r', child_DIE)
      
  print('}')
