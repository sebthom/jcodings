/*
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is furnished to do
 * so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.jcodings.unicode;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;

import org.jcodings.ApplyAllCaseFoldFunction;
import org.jcodings.CaseFoldCodeItem;
import org.jcodings.CodeRange;
import org.jcodings.Config;
import org.jcodings.IntHolder;
import org.jcodings.MultiByteEncoding;
import org.jcodings.constants.CharacterType;
import org.jcodings.exception.CharacterPropertyException;
import org.jcodings.exception.ErrorMessages;
import org.jcodings.util.ArrayReader;
import org.jcodings.util.CaseInsensitiveBytesHash;
import org.jcodings.util.IntArrayHash;
import org.jcodings.util.IntHash;


public abstract class UnicodeEncoding extends MultiByteEncoding {

    private static final int MAX_WORD_LENGTH = Config.USE_UNICODE_PROPERTIES ? 44 : 6;
    private static final int PROPERTY_NAME_MAX_SIZE = MAX_WORD_LENGTH + 1;

    protected UnicodeEncoding(String name, int minLength, int maxLength, int[]EncLen, int[][]Trans) {
        // ASCII type tables for all Unicode encodings
        super(name, minLength, maxLength, EncLen, Trans, UNICODE_ISO_8859_1_CTypeTable);
        isUnicode = true;
    }

    protected UnicodeEncoding(String name, int minLength, int maxLength, int[]EncLen) {
        this(name, minLength, maxLength, EncLen, null);
    }

    @Override
    public String getCharsetName() {
        return new String(getName());
    }

    // onigenc_unicode_is_code_ctype
    @Override
    public boolean isCodeCType(int code, int ctype) {
        if (Config.USE_UNICODE_PROPERTIES) {
            if (ctype <= CharacterType.MAX_STD_CTYPE && code < 256)
                return isCodeCTypeInternal(code, ctype);
        } else {
            if (code < 256) return isCodeCTypeInternal(code, ctype);
        }

        if (ctype > UnicodeProperties.CodeRangeTable.length) throw new InternalError(ErrorMessages.ERR_TYPE_BUG);

        return CodeRange.isInCodeRange(UnicodeProperties.CodeRangeTable[ctype].getRange(), code);

    }

    // onigenc_unicode_ctype_code_range
    protected final int[]ctypeCodeRange(int ctype) {
        if (ctype >= UnicodeProperties.CodeRangeTable.length) throw new InternalError(ErrorMessages.ERR_TYPE_BUG);

        return UnicodeProperties.CodeRangeTable[ctype].getRange();
    }

    // onigenc_unicode_property_name_to_ctype
    @Override
    public int propertyNameToCType(byte[]name, int p, int end) {
        byte[]buf = new byte[PROPERTY_NAME_MAX_SIZE];

        int p_ = p;
        int len = 0;

        while(p_ < end) {
            int code = mbcToCode(name, p_, end);
            if (code >= 0x80) throw new CharacterPropertyException(ErrorMessages.ERR_INVALID_CHAR_PROPERTY_NAME);
            buf[len++] = (byte)code;
            if (len >= PROPERTY_NAME_MAX_SIZE) throw new CharacterPropertyException(ErrorMessages.ERR_INVALID_CHAR_PROPERTY_NAME, name, p, end);
            p_ += length(name, p_, end);
        }

        Integer ctype = CTypeName.CTypeNameHash.get(buf, 0, len);
        if (ctype == null) throw new CharacterPropertyException(ErrorMessages.ERR_INVALID_CHAR_PROPERTY_NAME, name, p, end);
        return ctype;
    }

    // onigenc_unicode_mbc_case_fold
    @Override
    public int mbcCaseFold(int flag, byte[]bytes, IntHolder pp, int end, byte[]fold) {
        int p = pp.value;
        int foldP = 0;

        int code = mbcToCode(bytes, p, end);
        int len = length(bytes, p, end);
        pp.value += len;

        if (Config.USE_UNICODE_CASE_FOLD_TURKISH_AZERI) {
            if ((flag & Config.CASE_FOLD_TURKISH_AZERI) != 0) {
                if (code == 0x0049) {
                    return codeToMbc(0x0131, fold, foldP);
                } else if (code == 0x0130) {
                    return codeToMbc(0x0069, fold, foldP);
                }
            }
        }

        CodeList to = CaseFold.Hash.get(code);
        if (to != null) {
            if (to.codes.length == 1) {
                return codeToMbc(to.codes[0], fold, foldP);
            } else {
                int rlen = 0;
                for (int i=0; i<to.codes.length; i++) {
                    len = codeToMbc(to.codes[i], fold, foldP);
                    foldP += len;
                    rlen += len;
                }
                return rlen;
            }
        }

        for (int i=0; i<len; i++) {
            fold[foldP++] = bytes[p++];
        }
        return len;
    }

    // onigenc_unicode_apply_all_case_fold
    @Override
    public void applyAllCaseFold(int flag, ApplyAllCaseFoldFunction fun, Object arg) {
        /* if (CaseFoldInited == 0) init_case_fold_table(); */

        int[]code = new int[]{0};
        for (int i=0; i<CaseFold11.From.length; i++) {
            int from = CaseFold11.From[i];
            CodeList to = CaseFold11.To[i];

            for (int j=0; j<to.codes.length; j++) {
                code[0] = from;
                fun.apply(to.codes[j], code, 1, arg);

                code[0] = to.codes[j];
                fun.apply(from, code, 1, arg);

                for (int k=0; k<j; k++) {
                    code[0] = to.codes[k];
                    fun.apply(to.codes[j], code, 1, arg);

                    code[0] = to.codes[j];
                    fun.apply(to.codes[k], code, 1, arg);
                }

            }
        }

        if (Config.USE_UNICODE_CASE_FOLD_TURKISH_AZERI && (flag & Config.CASE_FOLD_TURKISH_AZERI) != 0) {
            code[0] = 0x0131;
            fun.apply(0x0049, code, 1, arg);
            code[0] = 0x0049;
            fun.apply(0x0131, code, 1, arg);
            code[0] = 0x0130;
            fun.apply(0x0069, code, 1, arg);
            code[0] = 0x0069;
            fun.apply(0x0130, code, 1, arg);
        } else {
            for (int i=0; i<CaseFold11.Locale_From.length; i++) {
                int from = CaseFold11.Locale_From[i];
                CodeList to = CaseFold11.Locale_To[i];

                for (int j=0; j<to.codes.length; j++) {
                    code[0] = from;
                    fun.apply(to.codes[j], code, 1, arg);

                    code[0] = to.codes[j];
                    fun.apply(from, code, 1, arg);

                    for (int k = 0; k<j; k++) {
                        code[0] = to.codes[k];
                        fun.apply(to.codes[j], code, 1, arg);

                        code[0] = to.codes[j];
                        fun.apply(to.codes[k], code, 1, arg);
                    }
                }
            }
        } // USE_UNICODE_CASE_FOLD_TURKISH_AZERI

        if ((flag & Config.INTERNAL_ENC_CASE_FOLD_MULTI_CHAR) != 0) {
            for (int i=0; i<CaseFold12.From.length; i++) {
                int[]from = CaseFold12.From[i];
                CodeList to = CaseFold12.To[i];
                for (int j=0; j<to.codes.length; j++) {
                    fun.apply(to.codes[j], from, 2, arg);

                    for (int k=0; k<to.codes.length; k++) {
                        if (k == j) continue;
                        code[0] = to.codes[k];
                        fun.apply(to.codes[j], code, 1, arg);
                    }
                }
            }

            if (!Config.USE_UNICODE_CASE_FOLD_TURKISH_AZERI || (flag & Config.CASE_FOLD_TURKISH_AZERI) == 0) {
                for (int i=0; i<CaseFold12.Locale_From.length; i++) {
                    int[]from = CaseFold12.Locale_From[i];
                    CodeList to = CaseFold12.Locale_To[i];
                    for (int j=0; j<to.codes.length; j++) {
                        fun.apply(to.codes[j], from, 2, arg);

                        for (int k=0; k<to.codes.length; k++) {
                            if (k == j) continue;
                            code[0] = to.codes[k];
                            fun.apply(to.codes[j], code, 1, arg);
                        }
                    }
                }
            } // !USE_UNICODE_CASE_FOLD_TURKISH_AZERI

            for (int i=0; i<CaseFold13.From.length; i++) {
                int[]from = CaseFold13.From[i];
                CodeList to = CaseFold13.To[i];

                for (int j=0; j<to.codes.length; j++) {
                    fun.apply(to.codes[j], from, 3, arg); //// ????

                    for (int k=0; k<to.codes.length; k++) {
                        if (k == j) continue;
                        code[0] = to.codes[k];
                        fun.apply(to.codes[j], code, 1, arg);
                    }
                }
            }

        } // INTERNAL_ENC_CASE_FOLD_MULTI_CHAR
    }

    // onigenc_unicode_get_case_fold_codes_by_str
    @Override
    public CaseFoldCodeItem[]caseFoldCodesByString(int flag, byte[]bytes, int p, int end) {
        int code = mbcToCode(bytes, p, end);
        int len = length(bytes, p, end);

        if (Config.USE_UNICODE_CASE_FOLD_TURKISH_AZERI) {
            if ((flag & Config.CASE_FOLD_TURKISH_AZERI) != 0) {
                if (code == 0x0049) {
                    return new CaseFoldCodeItem[]{new CaseFoldCodeItem(len, 1, new int[]{0x0131})};
                } else if(code == 0x0130) {
                    return new CaseFoldCodeItem[]{new CaseFoldCodeItem(len, 1, new int[]{0x0069})};
                } else if(code == 0x0131) {
                    return new CaseFoldCodeItem[]{new CaseFoldCodeItem(len, 1, new int[]{0x0049})};
                } else if(code == 0x0069) {
                    return new CaseFoldCodeItem[]{new CaseFoldCodeItem(len, 1, new int[]{0x0130})};
                }
            }
        } // USE_UNICODE_CASE_FOLD_TURKISH_AZERI

        int n = 0;
        int fn = 0;
        CodeList to = CaseFold.Hash.get(code);
        CaseFoldCodeItem[]items = null;
        if (to != null) {
            items = new CaseFoldCodeItem[Config.ENC_GET_CASE_FOLD_CODES_MAX_NUM];

            if (to.codes.length == 1) {
                int origCode = code;

                items[0] = new CaseFoldCodeItem(len, 1, new int[]{to.codes[0]});
                n++;

                code = to.codes[0];
                to = CaseFold11.Hash.get(code);

                if (to != null) {
                    for (int i=0; i<to.codes.length; i++) {
                        if (to.codes[i] != origCode) {
                            items[n] = new CaseFoldCodeItem(len, 1, new int[]{to.codes[i]});
                            n++;
                        }
                    }
                }
            } else if ((flag & Config.INTERNAL_ENC_CASE_FOLD_MULTI_CHAR) != 0) {
                int[][]cs = new int[3][4];
                int[]ncs = new int[3];

                for (fn=0; fn<to.codes.length; fn++) {
                    cs[fn][0] = to.codes[fn];
                    CodeList z3 = CaseFold11.Hash.get(cs[fn][0]);
                    if (z3 != null) {
                        for (int i=0; i<z3.codes.length; i++) {
                            cs[fn][i+1] = z3.codes[i];
                        }
                        ncs[fn] = z3.codes.length + 1;
                    } else {
                        ncs[fn] = 1;
                    }
                }

                if (fn == 2) {
                    for (int i=0; i<ncs[0]; i++) {
                        for (int j=0; j<ncs[1]; j++) {
                            items[n] = new CaseFoldCodeItem(len, 2, new int[]{cs[0][i], cs[1][j]});
                            n++;
                        }
                    }

                    CodeList z2 = CaseFold12.Hash.get(to.codes);
                    if (z2 != null) {
                        for (int i=0; i<z2.codes.length; i++) {
                            if (z2.codes[i] == code) continue;
                            items[n] = new CaseFoldCodeItem(len, 1, new int[]{z2.codes[i]});
                            n++;
                        }
                    }
                } else {
                    for (int i=0; i<ncs[0]; i++) {
                        for (int j=0; j<ncs[1]; j++) {
                            for (int k=0; k<ncs[2]; k++) {
                                items[n] = new CaseFoldCodeItem(len, 3, new int[]{cs[0][i], cs[1][j], cs[2][k]});
                                n++;
                            }
                        }
                    }
                    CodeList z2 = CaseFold13.Hash.get(to.codes);
                    if (z2 != null) {
                        for (int i=0; i<z2.codes.length; i++) {
                            if (z2.codes[i] == code) continue;
                            items[n] = new CaseFoldCodeItem(len, 1, new int[]{z2.codes[i]});
                            n++;
                        }
                    }
                }
                /* multi char folded code is not head of another folded multi char */
                flag = 0; /* DISABLE_CASE_FOLD_MULTI_CHAR(flag); */
            }
        } else {
            to = CaseFold11.Hash.get(code);
            if (to != null) {
                items = new CaseFoldCodeItem[Config.ENC_GET_CASE_FOLD_CODES_MAX_NUM];
                for (int i=0; i<to.codes.length; i++) {
                    items[n] = new CaseFoldCodeItem(len, 1, new int[]{to.codes[i]});
                    n++;
                }
            }
        }

        if ((flag & Config.INTERNAL_ENC_CASE_FOLD_MULTI_CHAR) != 0) {
            if (items == null) items = new CaseFoldCodeItem[Config.ENC_GET_CASE_FOLD_CODES_MAX_NUM];

            p += len;
            if (p < end) {
                final int codes0 = code;
                final int codes1;
                code = mbcToCode(bytes, p, end);
                to = CaseFold.Hash.get(code);
                if (to != null && to.codes.length == 1) {
                    codes1 = to.codes[0];
                } else {
                    codes1 = code;
                }

                int clen = length(bytes, p, end);
                len += clen;
                CodeList z2 = CaseFold12.Hash.get(codes0, codes1);
                if (z2 != null) {
                    for (int i=0; i<z2.codes.length; i++) {
                        items[n] = new CaseFoldCodeItem(len, 1, new int[]{z2.codes[i]});
                        n++;
                    }
                }

                p += clen;
                if (p < end) {
                    final int codes2;
                    code = mbcToCode(bytes, p, end);
                    to = CaseFold.Hash.get(code);
                    if (to != null && to.codes.length == 1) {
                        codes2 = to.codes[0];
                    } else {
                        codes2 = code;
                    }
                    clen = length(bytes, p, end);
                    len += clen;
                    z2 = CaseFold13.Hash.get(codes0, codes1, codes2);
                    if (z2 != null) {
                        for (int i=0; i<z2.codes.length; i++) {
                            items[n] = new CaseFoldCodeItem(len, 1, new int[]{z2.codes[i]});
                            n++;
                        }
                    }
                }
            }
        }

        if (items == null || n == 0) return EMPTY_FOLD_CODES;
        if (n < items.length) {
            CaseFoldCodeItem [] tmp = new CaseFoldCodeItem[n];
            System.arraycopy(items, 0, tmp, 0, n);
            return tmp;
        } else {
            return items;
        }
    }

    static final short UNICODE_ISO_8859_1_CTypeTable[] = {
          0x4008, 0x4008, 0x4008, 0x4008, 0x4008, 0x4008, 0x4008, 0x4008,
          0x4008, 0x428c, 0x4289, 0x4288, 0x4288, 0x4288, 0x4008, 0x4008,
          0x4008, 0x4008, 0x4008, 0x4008, 0x4008, 0x4008, 0x4008, 0x4008,
          0x4008, 0x4008, 0x4008, 0x4008, 0x4008, 0x4008, 0x4008, 0x4008,
          0x4284, 0x41a0, 0x41a0, 0x41a0, 0x41a0, 0x41a0, 0x41a0, 0x41a0,
          0x41a0, 0x41a0, 0x41a0, 0x41a0, 0x41a0, 0x41a0, 0x41a0, 0x41a0,
          0x78b0, 0x78b0, 0x78b0, 0x78b0, 0x78b0, 0x78b0, 0x78b0, 0x78b0,
          0x78b0, 0x78b0, 0x41a0, 0x41a0, 0x41a0, 0x41a0, 0x41a0, 0x41a0,
          0x41a0, 0x7ca2, 0x7ca2, 0x7ca2, 0x7ca2, 0x7ca2, 0x7ca2, 0x74a2,
          0x74a2, 0x74a2, 0x74a2, 0x74a2, 0x74a2, 0x74a2, 0x74a2, 0x74a2,
          0x74a2, 0x74a2, 0x74a2, 0x74a2, 0x74a2, 0x74a2, 0x74a2, 0x74a2,
          0x74a2, 0x74a2, 0x74a2, 0x41a0, 0x41a0, 0x41a0, 0x41a0, 0x51a0,
          0x41a0, 0x78e2, 0x78e2, 0x78e2, 0x78e2, 0x78e2, 0x78e2, 0x70e2,
          0x70e2, 0x70e2, 0x70e2, 0x70e2, 0x70e2, 0x70e2, 0x70e2, 0x70e2,
          0x70e2, 0x70e2, 0x70e2, 0x70e2, 0x70e2, 0x70e2, 0x70e2, 0x70e2,
          0x70e2, 0x70e2, 0x70e2, 0x41a0, 0x41a0, 0x41a0, 0x41a0, 0x4008,
          0x0008, 0x0008, 0x0008, 0x0008, 0x0008, 0x0288, 0x0008, 0x0008,
          0x0008, 0x0008, 0x0008, 0x0008, 0x0008, 0x0008, 0x0008, 0x0008,
          0x0008, 0x0008, 0x0008, 0x0008, 0x0008, 0x0008, 0x0008, 0x0008,
          0x0008, 0x0008, 0x0008, 0x0008, 0x0008, 0x0008, 0x0008, 0x0008,
          0x0284, 0x01a0, 0x00a0, 0x00a0, 0x00a0, 0x00a0, 0x00a0, 0x00a0,
          0x00a0, 0x00a0, 0x30e2, 0x01a0, 0x00a0, 0x00a8, 0x00a0, 0x00a0,
          0x00a0, 0x00a0, 0x10a0, 0x10a0, 0x00a0, 0x30e2, 0x00a0, 0x01a0,
          0x00a0, 0x10a0, 0x30e2, 0x01a0, 0x10a0, 0x10a0, 0x10a0, 0x01a0,
          0x34a2, 0x34a2, 0x34a2, 0x34a2, 0x34a2, 0x34a2, 0x34a2, 0x34a2,
          0x34a2, 0x34a2, 0x34a2, 0x34a2, 0x34a2, 0x34a2, 0x34a2, 0x34a2,
          0x34a2, 0x34a2, 0x34a2, 0x34a2, 0x34a2, 0x34a2, 0x34a2, 0x00a0,
          0x34a2, 0x34a2, 0x34a2, 0x34a2, 0x34a2, 0x34a2, 0x34a2, 0x30e2,
          0x30e2, 0x30e2, 0x30e2, 0x30e2, 0x30e2, 0x30e2, 0x30e2, 0x30e2,
          0x30e2, 0x30e2, 0x30e2, 0x30e2, 0x30e2, 0x30e2, 0x30e2, 0x30e2,
          0x30e2, 0x30e2, 0x30e2, 0x30e2, 0x30e2, 0x30e2, 0x30e2, 0x00a0,
          0x30e2, 0x30e2, 0x30e2, 0x30e2, 0x30e2, 0x30e2, 0x30e2, 0x30e2
    };

    static final class CodeRangeEntry {
        final String table;
        final byte[]name;
        int[]range;

        CodeRangeEntry(String name, String table) {
            this.table = table;
            this.name = name.getBytes();
        }

        public int[]getRange() {
            if (range == null) range = ArrayReader.readIntArray(table);
            return range;
        }
    }

    static class CTypeName {
        private static final CaseInsensitiveBytesHash<Integer> CTypeNameHash = initializeCTypeNameTable();

        private static CaseInsensitiveBytesHash<Integer> initializeCTypeNameTable() {
            CaseInsensitiveBytesHash<Integer> table = new CaseInsensitiveBytesHash<Integer>();
            for (int i = 0; i < UnicodeProperties.CodeRangeTable.length; i++) {
                table.putDirect(UnicodeProperties.CodeRangeTable[i].name, i);
            }
            return table;
        }
    }

    private static class CodeList {
        CodeList(DataInputStream dis) throws IOException {
            int packed = dis.readInt();
            this.flags = packed & ~Config.CodePointMask;
            int length = packed & Config.CodePointMask;
            this.codes = new int[length];
            for (int j = 0; j < length; j++) {
                codes[j] = dis.readInt();
            }
        }
        final int[]codes;
        final int flags;
    }

    private static class CaseFold {
        static IntHash<CodeList> read(String table) {
            try {
                DataInputStream dis = ArrayReader.openStream(table);
                int size = dis.readInt();
                IntHash<CodeList> hash = new IntHash<CodeList>(size);
                for (int i = 0; i < size; i++) {
                    hash.putDirect(dis.readInt(), new CodeList(dis));
                }
                dis.close();
                return hash;
            } catch (IOException iot) {
                throw new RuntimeException(iot);
            }
        }

        static final IntHash<CodeList>Hash = read("CaseFold");
    }

    private static class CaseFold11 {
        private static final int From[];
        private static final CodeList To[];
        private static final int Locale_From[];
        private static final CodeList Locale_To[];

        static Object[] read(String table) {
            try {
                DataInputStream dis = ArrayReader.openStream(table);
                int size = dis.readInt();
                int[]from = new int[size];
                CodeList[]to = new CodeList[size];
                for (int i = 0; i < size; i++) {
                    from[i] = dis.readInt();
                    to[i] = new CodeList(dis);
                }
                dis.close();
                return new Object[] {from, to};
            } catch (IOException iot) {
                throw new RuntimeException(iot);
            }
        }

        static {
            Object[]unfold;
            unfold = read("CaseUnfold_11");
            From = (int[])unfold[0];
            To = (CodeList[])unfold[1];
            unfold = read("CaseUnfold_11_Locale");
            Locale_From = (int[])unfold[0];
            Locale_To = (CodeList[])unfold[1];
        }

        static IntHash<CodeList> initializeUnfold1Hash() {
            IntHash<CodeList> hash = new IntHash<CodeList>(From.length + Locale_From.length);
            for (int i = 0; i < From.length; i++) {
                hash.putDirect(From[i], To[i]);
            }
            for (int i = 0; i < Locale_From.length; i++) {
                hash.putDirect(Locale_From[i], Locale_To[i]);
            }
            return hash;
        }
        static final IntHash<CodeList> Hash = initializeUnfold1Hash();
    }

    private static Object[] readFoldN(int fromSize, String table) {
        try {
            DataInputStream dis = ArrayReader.openStream(table);
            int size = dis.readInt();
            int[][]from = new int[size][];
            CodeList[]to = new CodeList[size];
            for (int i = 0; i < size; i++) {
                from[i] = new int[fromSize];
                for (int j = 0; j < fromSize; j++) {
                    from[i][j] = dis.readInt();
                }
                to[i] = new CodeList(dis);
            }
            dis.close();
            return new Object[] {from, to};
        } catch (IOException iot) {
            throw new RuntimeException(iot);
        }
    }

    private static class CaseFold12 {
        private static final int From[][];
        private static final CodeList To[];
        private static final int Locale_From[][];
        private static final CodeList Locale_To[];

        static {
            Object[]unfold;
            unfold = readFoldN(2, "CaseUnfold_12");
            From = (int[][])unfold[0];
            To = (CodeList[])unfold[1];
            unfold = readFoldN(2, "CaseUnfold_12_Locale");
            Locale_From = (int[][])unfold[0];
            Locale_To = (CodeList[])unfold[1];
        }

        private static IntArrayHash<CodeList> initializeUnfold2Hash() {
            IntArrayHash<CodeList> unfold2 = new IntArrayHash<CodeList>(From.length + Locale_From.length);
            for (int i = 0; i < From.length; i++) {
                unfold2.putDirect(From[i], To[i]);
            }
            for (int i = 0; i < Locale_From.length; i++) {
                unfold2.putDirect(Locale_From[i], Locale_To[i]);
            }
            return unfold2;
        }

        static final IntArrayHash<CodeList> Hash = initializeUnfold2Hash();
    }

    private static class CaseFold13 {
        private static final int From[][];
        private static final CodeList To[];

        static {
            Object[]unfold;
            unfold = readFoldN(3, "CaseUnfold_13");
            From = (int[][])unfold[0];
            To = (CodeList[])unfold[1];
        }

        private static IntArrayHash<CodeList> initializeUnfold3Hash() {
            IntArrayHash<CodeList> unfold3 = new IntArrayHash<CodeList>(From.length);
            for (int i = 0; i < From.length; i++) {
                unfold3.putDirect(From[i], To[i]);
            }
            return unfold3;
        }

        static final IntArrayHash<CodeList> Hash = initializeUnfold3Hash();
    }

    private static class CaseMappingSpecials {
        static ArrayList<int[]> read() {
            try {
                DataInputStream dis = ArrayReader.openStream("CaseMappingSpecials");
                int size = dis.readInt();
                ArrayList<int[]> values = new ArrayList<int[]>();
                for (int i = 0; i < size; i++) {
                    int packed = dis.readInt();
                    int length = packed >>> Config.SpecialsLengthOffset;
                    int[]codes = new int[length];
                    codes[0] = packed & ((1 << Config.SpecialsLengthOffset) - 1);
                    for (int j = 1; j < length; j++) {
                        i++;
                        codes[j] = dis.readInt();
                    }
                    values.add(codes);
                }
                return values;
            } catch (IOException ioe) {
                throw new RuntimeException(ioe);
            }
        }

        private static ArrayList<int[]> Values = read();
    }
}
