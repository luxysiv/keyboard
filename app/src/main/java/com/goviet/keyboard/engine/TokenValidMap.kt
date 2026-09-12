package com.goviet.keyboard.engine

object TokenValidMap {

    private const val TABLE_BITS = 14
    private const val TABLE_SIZE = 1 shl TABLE_BITS
    private const val TABLE_MASK = TABLE_SIZE - 1
    private val _table = LongArray(TABLE_SIZE)

    private fun packKey(s: String): Long {
        // Chars occupy bit positions 50..5 (5 bits each) — at most 10 chars.
        // Longer strings would shift into negative bit offsets (JVM shift
        // counts are taken mod 64, silently corrupting the key), so reject
        // them explicitly instead of relying on the current data never
        // reaching that length.  The empty string is also rejected: its key
        // 0L would collide with the ``_table`` empty-slot sentinel.
        if (s.isEmpty() || s.length > 10) return -1L
        var key = s.length.toLong() shl 55
        for (i in s.indices) {
            val idx = s[i].code - 'a'.code
            if (idx < 0 || idx >= 26) return -1L
            key = key or (idx.toLong() shl (50 - 5 * i))
        }
        return key
    }

    private fun hash(key: Long): Int =
        ((key * -0x61c88647L) xor (key ushr 32)).toInt() and TABLE_MASK

    private fun insert(key: Long) {
        if (key < 0) return
        var slot = hash(key)
        while (true) {
            if (_table[slot] == key) return
            if (_table[slot] == 0L) { _table[slot] = key; return }
            slot = (slot + 1) and TABLE_MASK
        }
    }

    fun isValidPrefix(s: String): Boolean {
        val key = packKey(s)
        if (key < 0) return false
        var slot = hash(key)
        while (true) {
            if (_table[slot] == key) return true
            if (_table[slot] == 0L) return false
            slot = (slot + 1) and TABLE_MASK
        }
    }

    fun isDisplayPrefixValid(display: String): Boolean {
        if (display.isEmpty()) return true
        val ascii = stripDiacritics(display)
        if (ascii.isEmpty()) return true
        return isValidPrefix(ascii)
    }

    private fun stripDiacritics(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            val base = VIET_TO_ASCII[c.code]
            if (base != null) { sb.append(base); continue }
            if (c.code in 0x61..0x7A) { sb.append(c); continue }
            if (c.code in 0x41..0x5A) { sb.append((c.code + 32).toChar()); continue }
            val decomposed = java.text.Normalizer.normalize(c.toString(), java.text.Normalizer.Form.NFD)
            for (ch in decomposed) {
                if (ch.code in 0x61..0x7A) { sb.append(ch); break }
                if (ch.code in 0x41..0x5A) { sb.append((ch.code + 32).toChar()); break }
            }
        }
        return sb.toString()
    }

    private val VIET_TO_ASCII: Array<Char?> = arrayOfNulls<Char?>(0x1EF9 + 1).also { arr ->
        val m = mapOf(
            'ă' to 'a', 'â' to 'a', 'đ' to 'd', 'ê' to 'e', 'ô' to 'o',
            'ơ' to 'o', 'ư' to 'u', 'á' to 'a', 'à' to 'a', 'ả' to 'a',
            'ã' to 'a', 'ạ' to 'a', 'ắ' to 'a', 'ằ' to 'a', 'ẳ' to 'a',
            'ẵ' to 'a', 'ặ' to 'a', 'ấ' to 'a', 'ầ' to 'a', 'ẩ' to 'a',
            'ẫ' to 'a', 'ậ' to 'a', 'é' to 'e', 'è' to 'e', 'ẻ' to 'e',
            'ẽ' to 'e', 'ẹ' to 'e', 'ế' to 'e', 'ề' to 'e', 'ể' to 'e',
            'ễ' to 'e', 'ệ' to 'e', 'í' to 'i', 'ì' to 'i', 'ỉ' to 'i',
            'ĩ' to 'i', 'ị' to 'i', 'ó' to 'o', 'ò' to 'o', 'ỏ' to 'o',
            'õ' to 'o', 'ọ' to 'o', 'ố' to 'o', 'ồ' to 'o', 'ổ' to 'o',
            'ỗ' to 'o', 'ộ' to 'o', 'ớ' to 'o', 'ờ' to 'o', 'ở' to 'o',
            'ỡ' to 'o', 'ợ' to 'o', 'ú' to 'u', 'ù' to 'u', 'ủ' to 'u',
            'ũ' to 'u', 'ụ' to 'u', 'ứ' to 'u', 'ừ' to 'u', 'ử' to 'u',
            'ữ' to 'u', 'ự' to 'u', 'ý' to 'y', 'ỳ' to 'y', 'ỷ' to 'y',
            'ỹ' to 'y', 'ỵ' to 'y',
        )
        for ((k, v) in m) arr[k.code] = v
    }

// Auto-generated: 1984 prefixes from 18342 syllables
    private val EMBEDDED = arrayOf(
        "a", "ac", "ach", "ai", "am", "an", "ang", "anh",
        "ao", "ap", "at", "au", "ay", "b", "ba", "bac",
        "bach", "bai", "bam", "ban", "bang", "banh", "bao", "bap",
        "bat", "bau", "bay", "be", "bec", "bech", "bem", "ben",
        "beng", "benh", "beo", "bep", "bet", "beu", "bi", "bia",
        "bic", "bich", "bie", "biec", "biem", "bien", "bieng", "biep",
        "biet", "bieu", "bim", "bin", "binh", "bip", "bit", "biu",
        "bo", "boa", "boac", "boan", "boang", "boat", "boc", "boi",
        "bom", "bon", "bong", "boo", "boon", "boong", "bop", "bot",
        "bu", "bua", "buac", "buan", "buc", "bui", "bum", "bun",
        "bung", "buo", "buoc", "buoi", "buom", "buon", "buong", "buop",
        "buot", "buou", "bup", "but", "buu", "buy", "buye", "buyet",
        "buyt", "by", "c", "ca", "cac", "cach", "cai", "cam",
        "can", "cang", "canh", "cao", "cap", "cat", "cau", "cay",
        "ce", "cem", "cen", "ceo", "cet", "ceu", "ch", "cha",
        "chac", "chach", "chai", "cham", "chan", "chang", "chanh", "chao",
        "chap", "chat", "chau", "chay", "che", "chec", "chech", "chem",
        "chen", "cheng", "chenh", "cheo", "chep", "chet", "cheu", "chi",
        "chia", "chic", "chich", "chie", "chiec", "chiem", "chien", "chieng",
        "chiep", "chiet", "chieu", "chim", "chin", "chinh", "chip", "chit",
        "chiu", "cho", "choa", "choac", "choai", "choan", "choang", "choat",
        "choc", "choe", "choen", "choet", "choi", "chom", "chon", "chong",
        "choo", "choon", "choong", "chop", "chot", "chu", "chua", "chuan",
        "chuc", "chue", "chuec", "chuech", "chuen", "chuenh", "chui", "chum",
        "chun", "chung", "chuo", "chuoc", "chuoi", "chuom", "chuon", "chuong",
        "chuop", "chuot", "chup", "chut", "chuu", "chuy", "chuye", "chuyen",
        "chuyet", "chy", "ci", "cia", "cic", "cich", "cie", "cien",
        "cim", "cin", "cinh", "co", "coa", "coac", "coc", "coe",
        "coen", "coi", "com", "con", "cong", "coo", "cooc", "coon",
        "coong", "cop", "cot", "cu", "cua", "cuac", "cuc", "cue",
        "cui", "cum", "cun", "cung", "cuo", "cuoc", "cuoi", "cuom",
        "cuon", "cuong", "cuop", "cuot", "cup", "cut", "cuu", "cuy",
        "cy", "d", "da", "dac", "dach", "dai", "dam", "dan",
        "dang", "danh", "dao", "dap", "dat", "dau", "day", "de",
        "dec", "dech", "dem", "den", "deng", "denh", "deo", "dep",
        "det", "deu", "di", "dia", "dic", "dich", "die", "diec",
        "diem", "dien", "dieng", "diep", "diet", "dieu", "dim", "din",
        "dinh", "dip", "dit", "diu", "do", "doa", "doac", "doai",
        "doan", "doang", "doanh", "doat", "doc", "doi", "dom", "don",
        "dong", "dop", "dot", "du", "dua", "duan", "duat", "duc",
        "due", "duen", "duenh", "dui", "dum", "dun", "dung", "duo",
        "duoc", "duoi", "duom", "duon", "duong", "duot", "duou", "dup",
        "dut", "duu", "duy", "duye", "duyen", "duyet", "dy", "dyn",
        "e", "ec", "ech", "em", "en", "eng", "enh", "eo",
        "ep", "et", "eu", "g", "ga", "gac", "gach", "gai",
        "gam", "gan", "gang", "ganh", "gao", "gap", "gat", "gau",
        "gay", "ge", "gem", "gen", "gh", "gha", "ghan", "ghanh",
        "ghao", "ghap", "ghat", "ghe", "ghec", "ghech", "ghem", "ghen",
        "ghenh", "gheo", "ghep", "ghet", "ghi", "ghia", "ghie", "ghiec",
        "ghien", "ghim", "ghin", "ghinh", "ghip", "ghit", "gho", "ghot",
        "ghu", "ghue", "gi", "gia", "giac", "giai", "giam", "gian",
        "giang", "gianh", "giao", "giap", "giat", "giau", "giay", "gie",
        "giec", "giem", "gien", "gieng", "gieo", "giep", "giet", "gieu",
        "gii", "giie", "giiec", "giien", "gio", "gioa", "gioc", "gioi",
        "gion", "giong", "giot", "giu", "giua", "giuc", "giue", "giui",
        "gium", "giun", "giuo", "giuoc", "giuon", "giuong", "giup", "giut",
        "giuu", "giy", "go", "goa", "goac", "goao", "goc", "goe",
        "goeo", "goi", "gom", "gon", "gong", "goo", "goon", "goong",
        "gop", "got", "gu", "gua", "guan", "guay", "guc", "gue",
        "guet", "gui", "gum", "gun", "gung", "guo", "guoc", "guoi",
        "guom", "guon", "guong", "guot", "gup", "gut", "guy", "guye",
        "guyen", "guyet", "guyt", "h", "ha", "hac", "hach", "hai",
        "ham", "han", "hang", "hanh", "hao", "hap", "hat", "hau",
        "hay", "he", "hec", "hech", "hem", "hen", "heng", "henh",
        "heo", "hep", "het", "heu", "hi", "hia", "hic", "hich",
        "hie", "hiec", "hiem", "hien", "hieng", "hiep", "hiet", "hieu",
        "him", "hin", "hinh", "hip", "hit", "hiu", "ho", "hoa",
        "hoac", "hoach", "hoai", "hoam", "hoan", "hoang", "hoanh", "hoao",
        "hoat", "hoay", "hoc", "hoe", "hoen", "hoet", "hoi", "hom",
        "hon", "hong", "hoo", "hooc", "hop", "hot", "hu", "hua",
        "huan", "huat", "huc", "hue", "huec", "huech", "huen", "huenh",
        "hui", "hum", "hun", "hung", "huo", "huoc", "huoi", "huom",
        "huon", "huong", "huot", "huou", "hup", "hut", "huu", "huy",
        "huyc", "huych", "huye", "huyen", "huyet", "huyn", "huynh", "huyt",
        "hy", "i", "ia", "ic", "ich", "ie", "iec", "iem",
        "ien", "ieng", "iep", "iet", "ieu", "im", "in", "inh",
        "ip", "it", "iu", "k", "ka", "kai", "kam", "kan",
        "kang", "kanh", "kao", "kap", "kat", "kay", "ke", "kec",
        "kech", "kem", "ken", "keng", "kenh", "keo", "kep", "ket",
        "keu", "kh", "kha", "khac", "khach", "khai", "kham", "khan",
        "khang", "khanh", "khao", "khap", "khat", "khau", "khay", "khe",
        "khec", "khem", "khen", "kheng", "khenh", "kheo", "khep", "khet",
        "kheu", "khi", "khia", "khic", "khich", "khie", "khiem", "khien",
        "khieng", "khiep", "khiet", "khieu", "khin", "khinh", "khit", "khiu",
        "kho", "khoa", "khoac", "khoai", "khoam", "khoan", "khoang", "khoanh",
        "khoao", "khoat", "khoay", "khoc", "khoe", "khoen", "khoeo", "khoet",
        "khoi", "khom", "khon", "khong", "khoo", "khoon", "khoong", "khop",
        "khot", "khu", "khua", "khuan", "khuang", "khuat", "khuay", "khuc",
        "khue", "khuec", "khuech", "khui", "khum", "khun", "khung", "khuo",
        "khuoc", "khuoi", "khuon", "khuong", "khuot", "khuou", "khut", "khuu",
        "khuy", "khuya", "khuye", "khuyen", "khuyet", "khuyn", "khuynh", "khuyp",
        "khuyu", "khy", "khye", "khyen", "ki", "kia", "kic", "kich",
        "kie", "kiem", "kien", "kieng", "kiep", "kiet", "kieu", "kim",
        "kin", "kinh", "kip", "kit", "kiu", "ko", "koa", "koai",
        "koay", "koc", "koe", "koeo", "koi", "kom", "kon", "kong",
        "kop", "kot", "ku", "kua", "kuau", "kuc", "kue", "kuen",
        "kuenh", "kui", "kum", "kun", "kung", "kuo", "kuoc", "kuon",
        "kuong", "ky", "kye", "kyet", "l", "la", "lac", "lach",
        "lai", "lam", "lan", "lang", "lanh", "lao", "lap", "lat",
        "lau", "lay", "le", "lec", "lech", "lem", "len", "leng",
        "lenh", "leo", "lep", "let", "leu", "li", "lia", "lic",
        "lich", "lie", "liec", "liem", "lien", "lieng", "liep", "liet",
        "lieu", "lim", "lin", "linh", "lip", "lit", "liu", "lo",
        "loa", "loac", "loai", "loan", "loang", "loanh", "loat", "loay",
        "loc", "loe", "loen", "loet", "loi", "lom", "lon", "long",
        "loo", "loon", "loong", "lop", "lot", "lu", "lua", "luac",
        "luan", "luat", "luay", "luc", "lue", "lui", "lum", "lun",
        "lung", "luo", "luoc", "luoi", "luom", "luon", "luong", "luot",
        "lup", "lut", "luu", "luy", "luya", "luyc", "luych", "luye",
        "luyen", "luyn", "luynh", "ly", "m", "ma", "mac", "mach",
        "mai", "mam", "man", "mang", "manh", "mao", "map", "mat",
        "mau", "may", "me", "mec", "mech", "mem", "men", "meng",
        "menh", "meo", "mep", "met", "meu", "mi", "mia", "mic",
        "mich", "mie", "miem", "mien", "mieng", "miet", "mieu", "mim",
        "min", "minh", "mip", "mit", "miu", "mo", "moa", "moao",
        "moay", "moc", "moi", "mom", "mon", "mong", "moo", "mooc",
        "moon", "moong", "mop", "mot", "mu", "mua", "muan", "muau",
        "muc", "mue", "muen", "mui", "mum", "mun", "mung", "muo",
        "muoc", "muoi", "muom", "muon", "muong", "muop", "muot", "muou",
        "mup", "mut", "muu", "muy", "muya", "muye", "muyen", "my",
        "mye", "myen", "myet", "n", "na", "nac", "nach", "nai",
        "nam", "nan", "nang", "nanh", "nao", "nap", "nat", "nau",
        "nay", "ne", "nec", "nech", "nem", "nen", "neng", "nenh",
        "neo", "nep", "net", "neu", "ng", "nga", "ngac", "ngach",
        "ngai", "ngam", "ngan", "ngang", "nganh", "ngao", "ngap", "ngat",
        "ngau", "ngay", "nge", "ngec", "ngech", "ngen", "nget", "ngh",
        "ngha", "nghan", "nghanh", "nghao", "nghau", "nghe", "nghec", "nghech",
        "nghen", "nghenh", "ngheo", "nghet", "ngheu", "nghi", "nghia", "nghic",
        "nghich", "nghie", "nghiem", "nghien", "nghieng", "nghiep", "nghiet", "nghieu",
        "nghim", "nghin", "nghinh", "nghit", "nghiu", "ngho", "nghoo", "nghu",
        "nghua", "nghui", "nghum", "nghun", "ngi", "ngia", "ngim", "ngiu",
        "ngo", "ngoa", "ngoac", "ngoach", "ngoai", "ngoam", "ngoan", "ngoang",
        "ngoanh", "ngoao", "ngoap", "ngoat", "ngoay", "ngoc", "ngoe", "ngoem",
        "ngoen", "ngoeo", "ngoet", "ngoi", "ngom", "ngon", "ngong", "ngoo",
        "ngoon", "ngoong", "ngop", "ngot", "ngu", "ngua", "nguan", "nguay",
        "nguc", "ngue", "nguec", "nguech", "ngui", "ngum", "ngun", "ngung",
        "nguo", "nguoc", "nguoi", "nguon", "nguong", "ngup", "ngut", "nguu",
        "nguy", "nguye", "nguyen", "nguyet", "nguyt", "nguyu", "ngy", "ngye",
        "ngyen", "nh", "nha", "nhac", "nhach", "nhai", "nham", "nhan",
        "nhang", "nhanh", "nhao", "nhap", "nhat", "nhau", "nhay", "nhe",
        "nhec", "nhech", "nhem", "nhen", "nhenh", "nheo", "nhep", "nhet",
        "nheu", "nhi", "nhia", "nhic", "nhich", "nhie", "nhiec", "nhiem",
        "nhien", "nhiep", "nhiet", "nhieu", "nhim", "nhin", "nhinh", "nhip",
        "nhit", "nhiu", "nho", "nhoa", "nhoai", "nhoam", "nhoan", "nhoang",
        "nhoap", "nhoat", "nhoay", "nhoc", "nhoe", "nhoen", "nhoet", "nhoi",
        "nhom", "nhon", "nhong", "nhop", "nhot", "nhu", "nhua", "nhuan",
        "nhuc", "nhue", "nhui", "nhum", "nhun", "nhung", "nhuo", "nhuoc",
        "nhuom", "nhuon", "nhuong", "nhuot", "nhut", "nhuy", "nhuye", "nhuyen",
        "nhy", "ni", "nia", "nic", "nich", "nie", "niem", "nien",
        "nieng", "niep", "niet", "nieu", "nim", "nin", "ninh", "nip",
        "nit", "niu", "no", "noa", "noan", "noc", "noe", "noen",
        "noi", "nom", "non", "nong", "noo", "noon", "noong", "nop",
        "not", "nu", "nua", "nuan", "nuay", "nuc", "nui", "num",
        "nun", "nung", "nuo", "nuoc", "nuoi", "nuom", "nuon", "nuong",
        "nuop", "nuot", "nup", "nut", "nuu", "nuy", "nuye", "nuyen",
        "ny", "o", "oa", "oac", "oach", "oai", "oam", "oan",
        "oang", "oanh", "oap", "oat", "oc", "oe", "oi", "om",
        "on", "ong", "oo", "ooc", "oon", "oong", "op", "ot",
        "p", "pa", "pac", "pai", "pam", "pan", "pang", "panh",
        "pao", "pap", "pat", "pau", "pe", "pen", "peo", "pet",
        "peu", "pi", "pia", "pie", "piec", "pim", "pin", "pit",
        "po", "poc", "pom", "poo", "poon", "poong", "pop", "pu",
        "pua", "pui", "pun", "puo", "puoc", "puoi", "put", "q",
        "qu", "qua", "quac", "quach", "quai", "quam", "quan", "quang",
        "quanh", "quao", "quap", "quat", "quau", "quay", "que", "quec",
        "quech", "quen", "quenh", "queo", "quet", "queu", "qui", "quit",
        "quo", "quoc", "quoi", "quon", "quong", "quot", "quy", "quyc",
        "quych", "quye", "quyen", "quyet", "quyn", "quynh", "quyt", "r",
        "ra", "rac", "rach", "rai", "ram", "ran", "rang", "ranh",
        "rao", "rap", "rat", "rau", "ray", "re", "rec", "rech",
        "rem", "ren", "reng", "renh", "reo", "rep", "ret", "reu",
        "ri", "ria", "ric", "rich", "rie", "riem", "rien", "rieng",
        "riet", "rieu", "rim", "rin", "rinh", "rip", "rit", "riu",
        "ro", "roa", "roac", "roan", "roang", "roay", "roc", "roe",
        "roet", "roi", "rom", "ron", "rong", "rop", "rot", "ru",
        "rua", "ruan", "ruang", "ruat", "ruay", "ruc", "rue", "rui",
        "rum", "run", "rung", "ruo", "ruoc", "ruoi", "ruom", "ruon",
        "ruong", "ruot", "ruou", "rup", "rut", "ruu", "ruy", "ruye",
        "ruyen", "ruyet", "ry", "ryn", "s", "sa", "sac", "sach",
        "sai", "sam", "san", "sang", "sanh", "sao", "sap", "sat",
        "sau", "say", "se", "sec", "sem", "sen", "senh", "seo",
        "sep", "set", "seu", "si", "sia", "sic", "sich", "sie",
        "siec", "siem", "sien", "sieng", "siet", "sieu", "sim", "sin",
        "sinh", "sip", "sit", "siu", "so", "soa", "soac", "soai",
        "soan", "soang", "soat", "soc", "soi", "som", "son", "song",
        "soo", "sooc", "soon", "soong", "sop", "sot", "su", "sua",
        "suan", "suat", "suau", "suc", "sue", "sui", "sum", "sun",
        "sung", "suo", "suoc", "suoi", "suon", "suong", "suot", "sup",
        "sut", "suu", "suy", "suye", "suyen", "suyt", "sy", "sye",
        "syen", "t", "ta", "tac", "tach", "tai", "tam", "tan",
        "tang", "tanh", "tao", "tap", "tat", "tau", "tay", "te",
        "tec", "tech", "tem", "ten", "teng", "tenh", "teo", "tep",
        "tet", "teu", "th", "tha", "thac", "thach", "thai", "tham",
        "than", "thang", "thanh", "thao", "thap", "that", "thau", "thay",
        "the", "thec", "thech", "them", "then", "thenh", "theo", "thep",
        "thet", "theu", "thi", "thia", "thic", "thich", "thie", "thiec",
        "thiem", "thien", "thieng", "thiep", "thiet", "thieu", "thim", "thin",
        "thinh", "thip", "thit", "thiu", "tho", "thoa", "thoac", "thoai",
        "thoan", "thoang", "thoat", "thoc", "thoe", "thoi", "thom", "thon",
        "thong", "thoo", "thoon", "thoong", "thop", "thot", "thu", "thua",
        "thuac", "thuan", "thuat", "thuc", "thue", "thuec", "thuech", "thui",
        "thum", "thun", "thung", "thuo", "thuoc", "thuoi", "thuom", "thuon",
        "thuong", "thuot", "thup", "thut", "thuu", "thuy", "thuye", "thuyen",
        "thuyet", "ti", "tia", "tic", "tich", "tie", "tiec", "tiem",
        "tien", "tieng", "tiep", "tiet", "tieu", "tim", "tin", "tinh",
        "tip", "tit", "tiu", "to", "toa", "toac", "toai", "toan",
        "toang", "toanh", "toat", "toay", "toc", "toe", "toen", "toet",
        "toi", "tom", "ton", "tong", "too", "toon", "toong", "top",
        "tot", "tr", "tra", "trac", "trach", "trai", "tram", "tran",
        "trang", "tranh", "trao", "trap", "trat", "trau", "tray", "tre",
        "trec", "trech", "trem", "tren", "treng", "trenh", "treo", "trep",
        "tret", "treu", "tri", "tria", "tric", "trich", "trie", "trien",
        "trieng", "triet", "trieu", "trin", "trinh", "trit", "triu", "tro",
        "troc", "troi", "trom", "tron", "trong", "trop", "trot", "tru",
        "trua", "truan", "truat", "truc", "trui", "trum", "trun", "trung",
        "truo", "truoc", "truoi", "truom", "truon", "truong", "truot", "trut",
        "truu", "truy", "truye", "truyen", "truyn", "try", "trye", "tryen",
        "tu", "tua", "tuan", "tuat", "tuc", "tue", "tuec", "tuech",
        "tuen", "tuenh", "tuet", "tui", "tum", "tun", "tung", "tuo",
        "tuoc", "tuoi", "tuom", "tuon", "tuong", "tuop", "tuot", "tuou",
        "tup", "tut", "tuu", "tuy", "tuya", "tuye", "tuyen", "tuyet",
        "tuyn", "tuyp", "tuyt", "ty", "tye", "tyen", "u", "ua",
        "uan", "uang", "uat", "uau", "uay", "uc", "ue", "uen",
        "uet", "ui", "um", "un", "ung", "uo", "uoc", "uoi",
        "uom", "uon", "uong", "uop", "uot", "uou", "up", "ut",
        "uu", "uy", "uyc", "uych", "uye", "uyen", "uyet", "uyn",
        "uynh", "v", "va", "vac", "vach", "vai", "vam", "van",
        "vang", "vanh", "vao", "vap", "vat", "vau", "vay", "ve",
        "vec", "vech", "vem", "ven", "veng", "venh", "veo", "vet",
        "veu", "vi", "via", "vic", "vich", "vie", "viec", "viem",
        "vien", "vieng", "viet", "vim", "vin", "vinh", "vip", "vit",
        "viu", "vo", "voa", "voan", "voc", "voe", "voi", "vom",
        "von", "vong", "voo", "vop", "vot", "vu", "vua", "vuc",
        "vue", "vuet", "vui", "vum", "vun", "vung", "vuo", "vuoc",
        "vuoi", "vuom", "vuon", "vuong", "vuot", "vut", "vuu", "vy",
        "x", "xa", "xac", "xach", "xai", "xam", "xan", "xang",
        "xanh", "xao", "xap", "xat", "xau", "xay", "xe", "xec",
        "xech", "xem", "xen", "xeng", "xenh", "xeo", "xep", "xet",
        "xeu", "xi", "xia", "xic", "xich", "xie", "xiec", "xiem",
        "xien", "xieng", "xiep", "xiet", "xieu", "xim", "xin", "xinh",
        "xip", "xit", "xiu", "xo", "xoa", "xoac", "xoach", "xoai",
        "xoam", "xoan", "xoang", "xoanh", "xoat", "xoay", "xoc", "xoe",
        "xoen", "xoet", "xoi", "xom", "xon", "xong", "xoo", "xoon",
        "xoong", "xop", "xot", "xu", "xua", "xuan", "xuat", "xuay",
        "xuc", "xue", "xuen", "xuenh", "xui", "xum", "xun", "xung",
        "xuo", "xuoc", "xuoi", "xuom", "xuon", "xuong", "xuot", "xup",
        "xut", "xuy", "xuya", "xuye", "xuyen", "xuyet", "xuyt", "xy",
        "xyt", "y", "ye", "yem", "yen", "yeng", "yet", "yeu",
        )

    init {
        for (prefix in EMBEDDED) insert(packKey(prefix))
    }
}
