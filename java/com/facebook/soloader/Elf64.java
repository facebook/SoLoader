/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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
 *
 * Este arquivo define constantes para manipulação de arquivos ELF64.
 * As classes internas representam as diferentes seções e cabeçalhos do formato ELF.
 * Todas as constantes são offsets baseados na especificação do ELF64.
 */

package com.facebook.soloader;

// Estruturas ELF64 contendo offsets para interpretação do formato ELF64.
final class Elf64 {

    // Constantes auxiliares para tamanhos de offsets
    private static final int OFFSET_SIZE_32BITS = 0x4;
    private static final int OFFSET_SIZE_64BITS = 0x8;

    // Classe representando a seção dinâmica
    static class Dyn {
        public static final int D_TAG = 0x0; // Tipo de tag (interpretação do d_un)
        public static final int D_UN = OFFSET_SIZE_64BITS; // Valor dinâmico
    }

    // Classe representando o cabeçalho ELF
    static class Ehdr {
        public static final int E_IDENT = 0x0; // Identificador ELF
        public static final int E_TYPE = 0x10; // Tipo do arquivo
        public static final int E_MACHINE = E_TYPE + OFFSET_SIZE_32BITS; // Tipo de arquitetura
        public static final int E_VERSION = 0x14; // Versão do arquivo
        public static final int E_ENTRY = 0x18; // Endereço de entrada
        public static final int E_PHOFF = 0x20; // Offset para cabeçalhos de programas
        public static final int E_SHOFF = 0x28; // Offset para cabeçalhos de seções
        public static final int E_FLAGS = 0x30; // Flags específicas do processador
        public static final int E_EHSIZE = 0x34; // Tamanho do cabeçalho ELF
        public static final int E_PHENTSIZE = 0x36; // Tamanho das entradas de programa
        public static final int E_PHNUM = 0x38; // Número de entradas no cabeçalho do programa
        public static final int E_SHENTSIZE = 0x3a; // Tamanho das entradas de seções
        public static final int E_SHNUM = 0x3c; // Número de entradas no cabeçalho de seções
        public static final int E_SHSTRNDX = 0x3e; // Índice da tabela de strings
    }

    // Classe representando o cabeçalho do programa
    static class Phdr {
        public static final int P_TYPE = 0x0; // Tipo do segmento
        public static final int P_FLAGS = OFFSET_SIZE_32BITS; // Flags do segmento
        public static final int P_OFFSET = OFFSET_SIZE_64BITS; // Offset no arquivo
        public static final int P_VADDR = 0x10; // Endereço virtual
        public static final int P_PADDR = 0x18; // Endereço físico
        public static final int P_FILESZ = 0x20; // Tamanho no arquivo
        public static final int P_MEMSZ = 0x28; // Tamanho na memória
        public static final int P_ALIGN = 0x30; // Alinhamento do segmento
    }

    // Classe representando o cabeçalho da seção
    static class Shdr {
        public static final int SH_NAME = 0x0; // Nome da seção (índice na tabela de strings)
        public static final int SH_TYPE = OFFSET_SIZE_32BITS; // Tipo da seção
        public static final int SH_FLAGS = OFFSET_SIZE_64BITS; // Flags da seção
        public static final int SH_ADDR = 0x10; // Endereço virtual
        public static final int SH_OFFSET = 0x18; // Offset no arquivo
        public static final int SH_SIZE = 0x20; // Tamanho em bytes
        public static final int SH_LINK = 0x28; // Índice de ligação
        public static final int SH_INFO = 0x2c; // Informações adicionais
        public static final int SH_ADDRALIGN = 0x30; // Alinhamento do endereço
        public static final int SH_ENTSIZE = 0x38; // Tamanho das entradas
    }

    /**
     * Valida se o identificador ELF está correto.
     * @param eIdent O array de bytes contendo o identificador ELF.
     * @return true se o identificador for válido, false caso contrário.
     */
    public static boolean isValidElf64(byte[] eIdent) {
        return eIdent.length >= 4 && eIdent[0] == 0x7F && eIdent[1] == 'E' && eIdent[2] == 'L' && eIdent[3] == 'F';
    }
}
