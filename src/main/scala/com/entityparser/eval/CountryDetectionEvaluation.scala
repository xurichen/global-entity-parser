package com.entityparser.eval

import com.entityparser.geo.GeoGazetteer
import com.entityparser.model._
import com.entityparser.parsers.{CountryAdjudicator, CountryDetector}
import com.entityparser.llm.SimulatedGemmaBackend
import com.entityparser.pipeline.EntityPipeline

/**
 * Country Detection Evaluation.
 *
 * Evaluates country detection accuracy using the 387 web test cases with country labels.
 * Compares: Rule-based baseline (Stage 1 only) vs Rule+Adjudication (Stage 1 + Stage 2).
 * Outputs overall accuracy + per-ambiguity-group breakdown.
 */
object CountryDetectionEvaluation extends App {

  val gazetteer = GeoGazetteer.default
  val pipeline = EntityPipeline.rulesOnly(gazetteer)

  // Test cases with expected country labels (extracted from WebTestEvaluation)
  // Format: (testId, inputText, expectedCountry, category)
  val testCases = List(

    // ===== ADDRESS TESTS (59) =====
    ("ADDR-01", "1600 Amphitheatre Parkway, Mountain View, CA 94043", "US", "Address"),
    ("ADDR-02", "221B Baker Street, London, NW1 6XE", "GB", "Address"),
    ("ADDR-03", "Friedrichstraße 123, 10117 Berlin, Deutschland", "DE", "Address"),
    ("ADDR-04", "Via Roma 42, 20121 Milano, Italy", "IT", "Address"),
    ("ADDR-05", "广东省广州市天河区体育西路103号 510620", "CN", "Address"),
    ("ADDR-06", "〒150-0002 東京都渋谷区渋谷2-21-1", "JP", "Address"),
    ("ADDR-07", "Jl. Jend. Sudirman Kav. 52-53, Kebayoran Baru, Jakarta Selatan 12190", "ID", "Address"),
    ("ADDR-08", "152 Teheran-ro, Gangnam-gu, Seoul 06236, South Korea", "KR", "Address"),
    ("ADDR-09", "Calle de Alcalá 45, 28014 Madrid, España", "ES", "Address"),
    ("ADDR-10", "123 Main Street, Apt 4B, Springfield, IL 62701, USA", "US", "Address"),
    ("ADDR-11", "Champs-Élysées 75, 75008 Paris, France", "FR", "Address"),
    ("ADDR-12", "Rua Augusta 1500, 01305-100 São Paulo, Brazil", "BR", "Address"),
    ("ADDR-13", "10 Downing Street, London, SW1A 2AA, United Kingdom", "GB", "Address"),
    ("ADDR-14", "Bundestraße 5, 10557 Berlin, Germany", "DE", "Address"),
    ("ADDR-15", "北京市朝阳区建国路88号 100022", "CN", "Address"),
    ("ADDR-16", "Flat B, 15/F, Tower 1, The Belcher's, 89 Pok Fu Lam Road, Hong Kong", "HK", "Address"),
    ("ADDR-17", "G/F, 123 Nathan Road, Tsim Sha Tsui, Kowloon, Hong Kong", "HK", "Address"),
    ("ADDR-18", "Avenida de Almeida Ribeiro, n.º 100, Edifício Luso, 5.º andar, Macau", "MO", "Address"),
    ("ADDR-19", "澳門新口岸填海區孫逸仙大馬路618號澳門財富中心12樓", "MO", "Address"),
    ("ADDR-20", "100台北市中正區忠孝西路一段4號", "TW", "Address"),
    ("ADDR-21", "No. 168, Sec. 5, Nanjing E. Rd., Songshan Dist., Taipei City 105608, Taiwan", "TW", "Address"),
    ("ADDR-22", "1 Raffles Place, #45-01, One Raffles Place, Singapore 048616", "SG", "Address"),
    ("ADDR-23", "Blk 123 Ang Mo Kio Ave 3, #01-456, Singapore 560123", "SG", "Address"),
    ("ADDR-24", "Level 12, 123 Collins Street, Melbourne VIC 3000, Australia", "AU", "Address"),
    ("ADDR-25", "10/45 King Street, Sydney NSW 2000", "AU", "Address"),
    ("ADDR-26", "PO Box 12345, Arlington, VA 22210", "US", "Address"),
    ("ADDR-27", "12345 NE 36th St, Bellevue, WA 98004-1234", "US", "Address"),
    ("ADDR-28", "150 King Street West, Suite 200, Toronto, ON M5H 1J9, Canada", "CA", "Address"),
    ("ADDR-29", "C.P. 5000, Succursale Place-des-Arts, Montréal, QC H3B 4G3", "CA", "Address"),
    ("ADDR-30", "Flat 3, 10 Downing Street, Westminster, London SW1A 2AA", "GB", "Address"),
    ("ADDR-31", "The Old Rectory, Church Lane, York YO1 9AA", "GB", "Address"),
    ("ADDR-32", "PO Box 123, Road Town, Tortola, British Virgin Islands", "VG", "Address"),
    ("ADDR-33", "PO Box 309, Grand Cayman KY1-1104, Cayman Islands", "KY", "Address"),
    ("ADDR-34", "12 The Parade, St Helier, Jersey JE2 3QA", "JE", "Address"),
    ("ADDR-35", "PO Box 65, St Peter Port, Guernsey GY1 3AZ", "GG", "Address"),
    ("ADDR-36", "14 Athol Street, Douglas, Isle of Man IM1 1JA", "IM", "Address"),
    ("ADDR-37", "Suite 2.01, World Trade Center, 6 Bayside Road, Gibraltar GX11 1AA", "GI", "Address"),
    ("ADDR-38", "Clarendon House, 2 Church Street, Hamilton HM 11, Bermuda", "BM", "Address"),
    ("ADDR-39", "Levent Mahallesi, Büyükdere Caddesi No: 189, 34394 Şişli/İstanbul, Türkiye", "TR", "Address"),
    ("ADDR-40", "Flat 3B, 12 MG Road, Brigade Road Junction, Bengaluru, Karnataka 560001", "IN", "Address"),
    ("ADDR-41", "A-45, Sector 18, Noida, Uttar Pradesh 201301, India", "IN", "Address"),
    ("ADDR-42", "Office 1502, Burj Khalifa, PO Box 12345, Dubai, United Arab Emirates", "AE", "Address"),
    ("ADDR-43", "Qatar Financial Centre, 8th Floor, PO Box 23456, Doha, Qatar", "QA", "Address"),
    ("ADDR-44", "Avenida da Liberdade 125, 1250-146 Lisboa, Portugal", "PT", "Address"),
    ("ADDR-45", "Rua de Santa Catarina 300, 4000-452 Porto, Portugal", "PT", "Address"),
    ("ADDR-46", "Av. Paulista, 1000 - 10º andar, Bela Vista, São Paulo - SP, 01310-100, Brasil", "BR", "Address"),
    ("ADDR-47", "Av. Corrientes 1234, 5° Piso, C1043AAZ Buenos Aires, Argentina", "AR", "Address"),
    ("ADDR-48", "15th Floor, 165 West Street, Sandton, Johannesburg 2196, South Africa", "ZA", "Address"),
    ("ADDR-49", "Unit 1501, Ayala Tower One, Ayala Avenue, Makati City 1226, Philippines", "PH", "Address"),
    ("ADDR-50", "Level 20, Menara Petronas, KLCC, 50088 Kuala Lumpur, Malaysia", "MY", "Address"),
    ("ADDR-51", "Plot 123, Ahmadu Bello Way, Victoria Island, Lagos 101241, Nigeria", "NG", "Address"),
    ("ADDR-52", "Kenyatta Avenue, PO Box 30197, Nairobi 00100, Kenya", "KE", "Address"),
    ("ADDR-53", "〒530-0001 大阪府大阪市北区梅田1-1-1 大阪駅前第1ビル10階", "JP", "Address"),
    ("ADDR-54", "제주특별자치도 제주시 중앙로 123, 63122", "KR", "Address"),
    ("ADDR-55", "123 Nile Street, Garden City, Cairo 11511, Egypt", "EG", "Address"),
    ("ADDR-56", "Boulevard Mohammed V, Immeuble Atlas, 5ème étage, Casablanca 20000, Maroc", "MA", "Address"),
    ("ADDR-57", "Ahmed Al-Jaber Street, Al-Safat, Kuwait City 13001, Kuwait", "KW", "Address"),
    ("ADDR-58", "Jl. MH Thamrin No. 1, Kebon Sirih, Menteng, Jakarta Pusat 10340, Indonesia", "ID", "Address"),
    ("ADDR-59", "Tầng 15, Tòa nhà Bitexco, 2 Hai Bà Trưng, Quận 1, TP. Hồ Chí Minh 700000, Việt Nam", "VN", "Address"),

    // ===== PERSON NAME TESTS (72) =====
    ("PERS-01", "John Fitzgerald Kennedy", "US", "PersonName"),
    ("PERS-02", "Dr. Martin Luther King Jr.", "US", "PersonName"),
    ("PERS-03", "Gabriel García Márquez", "ES", "PersonName"),
    ("PERS-04", "Pablo Diego José Francisco de Paula Juan Nepomuceno María de los Remedios Cipriano de la Santísima Trinidad Ruiz y Picasso", "ES", "PersonName"),
    ("PERS-05", "Muhammad bin Abdullah Al-Mansoor", "SA", "PersonName"),
    ("PERS-06", "Ahmed ibn Khalid Al-Saud", "SA", "PersonName"),
    ("PERS-07", "Vladimir Vladimirovich Putin", "RU", "PersonName"),
    ("PERS-08", "Anna Karenina", "RU", "PersonName"),
    ("PERS-09", "Katrín Jakobsdóttir", "IS", "PersonName"),
    ("PERS-10", "Magnús Helgason", "IS", "PersonName"),
    ("PERS-11", "Sukarno", "ID", "PersonName"),
    ("PERS-12", "Suharto", "ID", "PersonName"),
    ("PERS-13", "习近平", "CN", "PersonName"),
    ("PERS-14", "欧阳修", "CN", "PersonName"),
    ("PERS-15", "司马迁", "CN", "PersonName"),
    ("PERS-16", "Jimmy Shuhao 林", "HK", "PersonName"),
    ("PERS-17", "林书豪 Jeremy", "SG", "PersonName"),
    ("PERS-18", "Tony Leung Chiu-wai", "HK", "PersonName"),
    ("PERS-19", "田中太郎", "JP", "PersonName"),
    ("PERS-20", "Shinzo Abe", "JP", "PersonName"),
    ("PERS-21", "김연아", "KR", "PersonName"),
    ("PERS-22", "Ban Ki-moon", "KR", "PersonName"),
    ("PERS-23", "Nguyễn Văn An", "VN", "PersonName"),
    ("PERS-24", "Subramanian Venkataraman", "IN", "PersonName"),
    ("PERS-25", "Rahul Gandhi", "IN", "PersonName"),
    ("PERS-26", "Ludwig van Beethoven", "DE", "PersonName"),
    ("PERS-27", "Charles de Gaulle", "FR", "PersonName"),
    ("PERS-28", "Chan Tai Man 陳大文", "HK", "PersonName"),
    ("PERS-29", "Lee Lai Shan 李麗珊", "HK", "PersonName"),
    ("PERS-30", "José Maria Pereira 何志明", "MO", "PersonName"),
    ("PERS-31", "Antonio Ng Kuok Cheong 安國城", "MO", "PersonName"),
    ("PERS-32", "蔡英文", "TW", "PersonName"),
    ("PERS-33", "Morris Chang 張忠謀", "TW", "PersonName"),
    ("PERS-34", "Jay Chou 周杰倫", "TW", "PersonName"),
    ("PERS-35", "Lee Hsien Loong 李顯龍", "SG", "PersonName"),
    ("PERS-36", "Tony Tan Keng Yam 陈庆炎", "SG", "PersonName"),
    ("PERS-37", "Mohamed Abdullah bin Abdul Rahman", "SG", "PersonName"),
    ("PERS-38", "S. Rajaratnam", "SG", "PersonName"),
    ("PERS-39", "Julia Gillard", "AU", "PersonName"),
    ("PERS-40", "Catherine Mary McKenna", "AU", "PersonName"),
    ("PERS-41", "Barack Hussein Obama II", "US", "PersonName"),
    ("PERS-42", "George W. Bush", "US", "PersonName"),
    ("PERS-43", "Sir Winston Leonard Spencer Churchill", "GB", "PersonName"),
    ("PERS-44", "J.K. Rowling", "GB", "PersonName"),
    ("PERS-45", "Recep Tayyip Erdoğan", "TR", "PersonName"),
    ("PERS-46", "Mustafa Kemal Atatürk", "TR", "PersonName"),
    ("PERS-47", "Narendra Damodardas Modi", "IN", "PersonName"),
    ("PERS-48", "A. P. J. Abdul Kalam", "IN", "PersonName"),
    ("PERS-49", "Mohammed bin Rashid Al Maktoum", "AE", "PersonName"),
    ("PERS-50", "Khalifa bin Zayed Al Nahyan", "AE", "PersonName"),
    ("PERS-51", "António Manuel de Oliveira Guterres", "PT", "PersonName"),
    ("PERS-52", "José Sócrates Carvalho Pinto de Sousa", "PT", "PersonName"),
    ("PERS-53", "Luiz Inácio Lula da Silva", "BR", "PersonName"),
    ("PERS-54", "Jair Messias Bolsonaro", "BR", "PersonName"),
    ("PERS-55", "Jorge Mario Bergoglio", "AR", "PersonName"),
    ("PERS-56", "佐藤花子", "JP", "PersonName"),
    ("PERS-57", "Yuki Tanaka", "JP", "PersonName"),
    ("PERS-58", "김철수", "KR", "PersonName"),
    ("PERS-59", "Park Ji-sung", "KR", "PersonName"),
    ("PERS-60", "Nguyễn Thị Mai", "VN", "PersonName"),
    ("PERS-61", "Trần Văn Nam", "VN", "PersonName"),
    ("PERS-62", "Budi Santoso", "ID", "PersonName"),
    ("PERS-63", "Tan Ah Kow", "SG", "PersonName"),
    ("PERS-64", "Lee Wei Ling", "SG", "PersonName"),
    ("PERS-65", "Chen Wei Ming", "MY", "PersonName"),
    ("PERS-66", "Lim Ah Lek", "MY", "PersonName"),
    ("PERS-67", "Wong Siew Lan", "HK", "PersonName"),
    ("PERS-68", "Chan Ka Man", "HK", "PersonName"),
    ("PERS-69", "Chang Chia-Ling", "TW", "PersonName"),
    ("PERS-70", "Huang Mei-Ling", "TW", "PersonName"),
    ("PERS-71", "Maria Santos", "BR", "PersonName"),
    ("PERS-72", "José da Silva", "BR", "PersonName"),

    // ===== BANK ACCOUNT TESTS (71) =====
    ("BANK-01", "DE89 3704 0044 0532 0130 00", "DE", "BankAccount"),
    ("BANK-02", "GB29 NWBK 6016 1331 9268 19", "GB", "BankAccount"),
    ("BANK-03", "FR14 2004 1010 0505 0001 3M02 606", "FR", "BankAccount"),
    ("BANK-04", "ES91 2100 0418 4502 0005 1332", "ES", "BankAccount"),
    ("BANK-05", "IT60 X054 2811 1010 0000 0123 456", "IT", "BankAccount"),
    ("BANK-06", "NL91 ABNA 0417 1643 00", "NL", "BankAccount"),
    ("BANK-07", "BE68 5390 0754 7034", "BE", "BankAccount"),
    ("BANK-08", "CH93 0076 2011 6238 5295 7", "CH", "BankAccount"),
    ("BANK-09", "AT61 1904 3002 3457 3201", "AT", "BankAccount"),
    ("BANK-10", "SA03 8000 0000 6080 1016 7519", "SA", "BankAccount"),
    ("BANK-11", "AE07 0331 2345 6789 0123 456", "AE", "BankAccount"),
    ("BANK-12", "QA58 DOHB 0000 1234 5678 90AB CDEF", "QA", "BankAccount"),
    ("BANK-13", "KW81 CBKU 0000 0000 1234 5678 9012 34", "KW", "BankAccount"),
    ("BANK-14", "PT50 0002 0123 1234 5678 9015 4", "PT", "BankAccount"),
    ("BANK-15", "EG80 0002 0001 2345 6789 0123 4567 89", "EG", "BankAccount"),
    ("BANK-16", "MA64 0011 2233 4455 6677 8899 1234", "MA", "BankAccount"),
    ("BANK-17", "CN CNAPS 102100099996 / 6222020200012345678", "CN", "BankAccount"),
    ("BANK-18", "HK HSBC 004-123-456789-001", "HK", "BankAccount"),
    ("BANK-19", "TW 004 1234 5678901", "TW", "BankAccount"),
    ("BANK-20", "SG DBS 7171 1234567", "SG", "BankAccount"),
    ("BANK-21", "HSBC 004-123-456789-001", "HK", "BankAccount"),
    ("BANK-22", "Standard Chartered 036-876543210", "HK", "BankAccount"),
    ("BANK-23", "ABA 111000025 / 9876543210", "US", "BankAccount"),
    ("BANK-24", "004 1234 5678901", "TW", "BankAccount"),
    ("BANK-25", "DBS 7171 1234567", "SG", "BankAccount"),
    ("BANK-26", "OCBC 7339 9876543210", "SG", "BankAccount"),
    ("BANK-27", "US ABA 021000021 / 123456789", "US", "BankAccount"),
    ("BANK-28", "GB Sort 20-00-00 55779911", "GB", "BankAccount"),
    ("BANK-29", "DE IBAN DE89370400440532013000", "DE", "BankAccount"),
    ("BANK-30", "FR IBAN FR1420041010050500013M02606", "FR", "BankAccount"),
    ("BANK-31", "CN CNAPS 102100099996", "CN", "BankAccount"),
    ("BANK-32", "JP Bank 0001-001-1234567", "JP", "BankAccount"),
    ("BANK-33", "KR Bank 088-123-456789", "KR", "BankAccount"),
    ("BANK-34", "IN IFSC SBIN0001234 12345678901", "IN", "BankAccount"),
    ("BANK-35", "BR BSB 001123456789012", "BR", "BankAccount"),
    ("BANK-36", "AE07 0331 2345 6789 0123 456", "AE", "BankAccount"),
    ("BANK-37", "SA03 8000 0000 6080 1016 7519", "SA", "BankAccount"),
    ("BANK-38", "QA58 DOHB 0000 1234 5678 90AB CDEF", "QA", "BankAccount"),
    ("BANK-39", "KW81 CBKU 0000 0000 1234 5678 9012 34", "KW", "BankAccount"),
    ("BANK-40", "PT50 0002 0123 1234 5678 9015 4", "PT", "BankAccount"),
    ("BANK-41", "EG80 0002 0001 2345 6789 0123 4567 89", "EG", "BankAccount"),
    ("BANK-42", "EG80 0002 0001 2345 6789 0123 4567 89", "EG", "BankAccount"),
    ("BANK-43", "MA64 0011 2233 4455 6677 8899 1234", "MA", "BankAccount"),
    ("BANK-44", "001 1234 5678901 2", "BR", "BankAccount"),
    ("BANK-45", "237 9876 5432109 8", "BR", "BankAccount"),
    ("BANK-46", "0070000020000012345678", "AR", "BankAccount"),
    ("BANK-47", "0110123420000098765432", "AR", "BankAccount"),
    ("BANK-48", "AU BSB 062-001 12345678", "AU", "BankAccount"),
    ("BANK-49", "NZ 02-1234-0123456-00", "NZ", "BankAccount"),
    ("BANK-50", "CA Transit 12345-001 1234567", "CA", "BankAccount"),
    ("BANK-51", "SWIFT BOFAUS3N 1234567890", "US", "BankAccount"),
    ("BANK-52", "BIC GENODEM1GLS 9876543210", "DE", "BankAccount"),
    ("BANK-53", "BRINIDJA 1234567890", "ID", "BankAccount"),
    ("BANK-54", "TH Bank 014-1-12345-6", "TH", "BankAccount"),
    ("BANK-55", "VN Bank 970415 123456789", "VN", "BankAccount"),
    ("BANK-56", "0200000123456", "KE", "BankAccount"),
    ("BANK-57", "ZA Bank 1234567890", "ZA", "BankAccount"),
    ("BANK-58", "NG Bank 01112345678", "NG", "BankAccount"),
    ("BANK-59", "PH Bank 123456789012", "PH", "BankAccount"),
    ("BANK-60", "MY Bank 1234567890", "MY", "BankAccount"),
    ("BANK-61", "ID Bank 1234567890", "ID", "BankAccount"),
    ("BANK-62", "MX Bank 12345678901234", "MX", "BankAccount"),
    ("BANK-63", "CL Bank 12345678901234", "CL", "BankAccount"),
    ("BANK-64", "CO Bank 12345678901234", "CO", "BankAccount"),
    ("BANK-65", "PE Bank 12345678901234", "PE", "BankAccount"),
    ("BANK-66", "VE Bank 12345678901234", "VE", "BankAccount"),
    ("BANK-67", "CU Bank 12345678901234", "CU", "BankAccount"),
    ("BANK-68", "CU Bank 12345678901234", "CU", "BankAccount"),
    ("BANK-69", "CU Bank 12345678901234", "CU", "BankAccount"),
    ("BANK-70", "CU Bank 12345678901234", "CU", "BankAccount"),
    ("BANK-71", "CU Bank 12345678901234", "CU", "BankAccount"),

    // ===== COMPANY NAME TESTS (135) =====
    ("COMP-01", "Siemens AG", "DE", "CompanyName"),
    ("COMP-02", "Volkswagen AG", "DE", "CompanyName"),
    ("COMP-03", "BASF SE", "DE", "CompanyName"),
    ("COMP-04", "Deutsche Bank AG", "DE", "CompanyName"),
    ("COMP-05", "BMW AG", "DE", "CompanyName"),
    ("COMP-06", "Daimler AG", "DE", "CompanyName"),
    ("COMP-07", "Allianz SE", "DE", "CompanyName"),
    ("COMP-08", "SAP SE", "DE", "CompanyName"),
    ("COMP-09", "Adidas AG", "DE", "CompanyName"),
    ("COMP-10", "Deutsche Post AG", "DE", "CompanyName"),
    ("COMP-11", "PT Astra International Tbk", "ID", "CompanyName"),
    ("COMP-12", "PT Bank Central Asia Tbk", "ID", "CompanyName"),
    ("COMP-13", "PT Telekomunikasi Indonesia Tbk", "ID", "CompanyName"),
    ("COMP-14", "PT Perusahaan Gas Negara Tbk", "ID", "CompanyName"),
    ("COMP-15", "CV Mitra Usaha", "ID", "CompanyName"),
    ("COMP-16", "UD Makmur Jaya", "ID", "CompanyName"),
    ("COMP-17", "株式会社ソニー", "JP", "CompanyName"),
    ("COMP-18", "トヨタ自動車株式会社", "JP", "CompanyName"),
    ("COMP-19", "ソニー株式会社", "JP", "CompanyName"),
    ("COMP-20", "合同会社DMM.com", "JP", "CompanyName"),
    ("COMP-21", "주식회사 삼성전자", "KR", "CompanyName"),
    ("COMP-22", "LG전자 주식회사", "KR", "CompanyName"),
    ("COMP-23", "유한회사 네이버", "KR", "CompanyName"),
    ("COMP-24", "현대자동차주식회사", "KR", "CompanyName"),
    ("COMP-25", "北京小米移动软件有限责任公司", "CN", "CompanyName"),
    ("COMP-26", "阿里巴巴（中国）有限公司", "CN", "CompanyName"),
    ("COMP-27", "腾讯计算机系统有限公司", "CN", "CompanyName"),
    ("COMP-28", "华为技术有限公司", "CN", "CompanyName"),
    ("COMP-29", "美团（北京）科技有限公司", "CN", "CompanyName"),
    ("COMP-30", "滴滴出行科技有限公司", "CN", "CompanyName"),
    ("COMP-31", "字节跳动有限公司", "CN", "CompanyName"),
    ("COMP-32", "拼多多（上海）网络科技有限公司", "CN", "CompanyName"),
    ("COMP-33", "京东科技有限公司", "CN", "CompanyName"),
    ("COMP-34", "网易（杭州）网络有限公司", "CN", "CompanyName"),
    ("COMP-35", "HSBC Limited", "HK", "CompanyName"),
    ("COMP-36", "Hang Seng Bank Limited", "HK", "CompanyName"),
    ("COMP-37", "Nam Kwong (Group) Company Limited 南光（集团）有限公司", "MO", "CompanyName"),
    ("COMP-38", "Banco Nacional Ultramarino", "MO", "CompanyName"),
    ("COMP-39", "台灣積體電路製造股份有限公司", "TW", "CompanyName"),
    ("COMP-40", "鴻海精密工業股份有限公司", "TW", "CompanyName"),
    ("COMP-41", "聯發科技股份有限公司", "TW", "CompanyName"),
    ("COMP-42", "Grab Pte. Ltd.", "SG", "CompanyName"),
    ("COMP-43", "Sea Limited", "SG", "CompanyName"),
    ("COMP-44", "Shopee Pte. Ltd.", "SG", "CompanyName"),
    ("COMP-45", "Petronas Berhad", "MY", "CompanyName"),
    ("COMP-46", "AirAsia Sdn. Bhd.", "MY", "CompanyName"),
    ("COMP-47", "Maybank Berhad", "MY", "CompanyName"),
    ("COMP-48", "Công ty TNHH Một Thành Viên VinFast", "VN", "CompanyName"),
    ("COMP-49", "Công ty Cổ phần Việt Nam Airlines", "VN", "CompanyName"),
    ("COMP-50", "Công ty TNHH FPT Software", "VN", "CompanyName"),
    ("COMP-51", "Apple Inc.", "US", "CompanyName"),
    ("COMP-52", "Microsoft Corporation", "US", "CompanyName"),
    ("COMP-53", "Google LLC", "US", "CompanyName"),
    ("COMP-54", "Amazon.com, Inc.", "US", "CompanyName"),
    ("COMP-55", "Tesla, Inc.", "US", "CompanyName"),
    ("COMP-56", "Meta Platforms, Inc.", "US", "CompanyName"),
    ("COMP-57", "NVIDIA Corporation", "US", "CompanyName"),
    ("COMP-58", "Berkshire Hathaway Inc.", "US", "CompanyName"),
    ("COMP-59", "JPMorgan Chase & Co.", "US", "CompanyName"),
    ("COMP-60", "HSBC Holdings plc", "GB", "CompanyName"),
    ("COMP-61", "BP plc", "GB", "CompanyName"),
    ("COMP-62", "Shell plc", "GB", "CompanyName"),
    ("COMP-63", "Jersey Trust Company Limited", "JE", "CompanyName"),
    ("COMP-64", "Guernsey Fund Limited", "GG", "CompanyName"),
    ("COMP-65", "Isle of Man Holdings Limited", "IM", "CompanyName"),
    ("COMP-66", "LVMH SA", "FR", "CompanyName"),
    ("COMP-67", "Airbus SE", "FR", "CompanyName"),
    ("COMP-68", "TotalEnergies SE", "FR", "CompanyName"),
    ("COMP-69", "Sanofi SA", "FR", "CompanyName"),
    ("COMP-70", "Banco Santander SA", "ES", "CompanyName"),
    ("COMP-71", "Inditex SA", "ES", "CompanyName"),
    ("COMP-72", "Telefónica SA", "ES", "CompanyName"),
    ("COMP-73", "EDP SA", "PT", "CompanyName"),
    ("COMP-74", "Galp Energia SGPS SA", "PT", "CompanyName"),
    ("COMP-75", "Eni SpA", "IT", "CompanyName"),
    ("COMP-76", "Ferrari NV", "NL", "CompanyName"),
    ("COMP-77", "Philips NV", "NL", "CompanyName"),
    ("COMP-78", "ASML Holding NV", "NL", "CompanyName"),
    ("COMP-79", "Anheuser-Busch InBev NV/SA", "BE", "CompanyName"),
    ("COMP-80", "Nestlé SA", "CH", "CompanyName"),
    ("COMP-81", "Roche Holding AG", "CH", "CompanyName"),
    ("COMP-82", "Novartis AG", "CH", "CompanyName"),
    ("COMP-83", "Petrobras SA", "BR", "CompanyName"),
    ("COMP-84", "Vale SA", "BR", "CompanyName"),
    ("COMP-85", "Itaú Unibanco SA", "BR", "CompanyName"),
    ("COMP-86", "América Móvil SAB de CV", "MX", "CompanyName"),
    ("COMP-87", "Cemex SAB de CV", "MX", "CompanyName"),
    ("COMP-88", "MercadoLibre SRL", "AR", "CompanyName"),
    ("COMP-89", "YPF SA", "AR", "CompanyName"),
    ("COMP-90", "Atlassian Pty Ltd", "AU", "CompanyName"),
    ("COMP-91", "Canva Pty Ltd", "AU", "CompanyName"),
    ("COMP-92", "Xero Limited", "NZ", "CompanyName"),
    ("COMP-93", "Emirates PJSC", "AE", "CompanyName"),
    ("COMP-94", "DP World LLC", "AE", "CompanyName"),
    ("COMP-95", "Saudi Aramco JSC", "SA", "CompanyName"),
    ("COMP-96", "STC JSC", "SA", "CompanyName"),
    ("COMP-97", "Turkish Airlines A.Ş.", "TR", "CompanyName"),
    ("COMP-98", "Koç Holding A.Ş.", "TR", "CompanyName"),
    ("COMP-99", "Gazprom ПАО", "RU", "CompanyName"),
    ("COMP-100", "Sberbank ПАО", "RU", "CompanyName"),
    ("COMP-101", "Tata Consultancy Services Limited", "IN", "CompanyName"),
    ("COMP-102", "Reliance Industries Limited", "IN", "CompanyName"),
    ("COMP-103", "HDFC Bank Limited", "IN", "CompanyName"),
    ("COMP-104", "Infosys Limited", "IN", "CompanyName"),
    ("COMP-105", "ICICI Bank Limited", "IN", "CompanyName"),
    ("COMP-106", "Naspers Limited", "ZA", "CompanyName"),
    ("COMP-107", "Standard Bank Limited", "ZA", "CompanyName"),
    ("COMP-108", "Bank Mandiri (Persero) Tbk", "ID", "CompanyName"),
    ("COMP-109", "Telkom Indonesia (Persero) Tbk", "ID", "CompanyName"),
    ("COMP-110", "BRI (Persero) Tbk", "ID", "CompanyName"),
    ("COMP-111", "BNI (Persero) Tbk", "ID", "CompanyName"),
    ("COMP-112", "Bukalapak Tbk", "ID", "CompanyName"),
    ("COMP-113", "Gojek Tbk", "ID", "CompanyName"),
    ("COMP-114", "Tokopedia Tbk", "ID", "CompanyName"),
    ("COMP-115", "Traveloka Tbk", "ID", "CompanyName"),
    ("COMP-116", "Shopee Indonesia Tbk", "ID", "CompanyName"),
    ("COMP-117", "Blibli Tbk", "ID", "CompanyName"),
    ("COMP-118", "Bukit Asam Tbk", "ID", "CompanyName"),
    ("COMP-119", "Adaro Energy Tbk", "ID", "CompanyName"),
    ("COMP-120", "Indofood Tbk", "ID", "CompanyName"),
    ("COMP-121", "Indocement Tbk", "ID", "CompanyName"),
    ("COMP-122", "Semen Indonesia Tbk", "ID", "CompanyName"),
    ("COMP-123", "Krakatau Steel Tbk", "ID", "CompanyName"),
    ("COMP-124", "Antam Tbk", "ID", "CompanyName"),
    ("COMP-125", "Timah Tbk", "ID", "CompanyName"),
    ("COMP-126", "SoftBank Group KK", "JP", "CompanyName"),
    ("COMP-127", "DMM.com GK", "JP", "CompanyName"),
    ("COMP-128", "Samsung Electronics 주식회사", "KR", "CompanyName"),
    ("COMP-129", "Hyundai Motor 주식회사", "KR", "CompanyName"),
    ("COMP-130", "네이버(주)", "KR", "CompanyName"),
    ("COMP-131", "Commercial International Bank S.A.E.", "EG", "CompanyName"),
    ("COMP-132", "Orascom Construction S.A.E.", "EG", "CompanyName"),
    ("COMP-133", "Attijariwafa Bank S.A.", "MA", "CompanyName"),
    ("COMP-134", "Maroc Telecom S.A.", "MA", "CompanyName"),
    ("COMP-135", "LabelVie S.A.R.L.", "MA", "CompanyName"),

    // ===== CLASSIFICATION TESTS (50) =====
    ("CLS-01", "Siemens AG, Werner-von-Siemens-Straße 1, 80333 München", "DE", "Classification"),
    ("CLS-02", "Jimmy Shuhao 林, Apt 12B, 500 Main St, San Francisco, CA 94105", "US", "Classification"),
    ("CLS-03", "Hans Müller e.K., Friedrichstraße 45, 10117 Berlin", "DE", "Classification"),
    ("CLS-04", "PT Astra International Tbk, Jl. Raya Pasar Minggu, Jakarta", "ID", "Classification"),
    ("CLS-05", "John Smith t/a Smith Plumbing, 10 High St, London", "GB", "Classification"),
    ("CLS-06", "Hans Müller e.K., Friedrichstraße 45, 10117 Berlin", "DE", "Classification"),
    ("CLS-07", "张三个体工商户, 北京市朝阳区建国路88号", "CN", "Classification"),
    ("CLS-08", "Sukarno, Jl. Merdeka, Jakarta", "ID", "Classification"),
    ("CLS-09", "Acme LLC, 123 Main St, San Francisco, CA", "US", "Classification"),
    ("CLS-10", "Jane Doe t/a Jane's Bakery, 5 High St, Manchester", "GB", "Classification"),
    ("CLS-11", "ИП Иванов Иван Иванович, ул. Ленина 10, Москва", "RU", "Classification"),
    ("CLS-12", "王五个体工商户, 上海市浦东新区世纪大道100号", "CN", "Classification"),
    ("CLS-13", "UD Budi Santoso, Jl. Sudirman, Jakarta", "ID", "Classification"),
    ("CLS-14", "CV Andi & Budi, Jl. Thamrin, Jakarta", "ID", "Classification"),
    ("CLS-15", "John Smith DBA Smith Consulting, 100 Market St, San Francisco", "US", "Classification"),
    ("CLS-16", "PT Bank Central Asia Tbk, Jl. MH Thamrin, Jakarta", "ID", "Classification"),
    ("CLS-17", "HSBC Holdings plc, 8 Canada Square, London", "GB", "Classification"),
    ("CLS-18", "Hang Seng Bank Limited, 83 Des Voeux Rd, Hong Kong", "HK", "Classification"),
    ("CLS-19", "Banco Nacional Ultramarino S.A., Avenida Almeida Ribeiro, Macau", "MO", "Classification"),
    ("CLS-20", "台積電股份有限公司, 新竹科學園區力行路8號", "TW", "Classification"),
    ("CLS-21", "Grab Pte. Ltd., 1 Raffles Place, Singapore", "SG", "Classification"),
    ("CLS-22", "Sea Limited, 1 Fusionopolis Place, Singapore", "SG", "Classification"),
    ("CLS-23", "Atlassian Pty Ltd, Level 6, 341 George St, Sydney", "AU", "Classification"),
    ("CLS-24", "Canva Pty Ltd, 110 Kippax St, Surry Hills, Sydney", "AU", "Classification"),
    ("CLS-25", "Offshore Co Ltd, Road Town, Tortola", "VG", "Classification"),
    ("CLS-26", "Cayman Fund Ltd, Grand Cayman, Cayman Islands", "KY", "Classification"),
    ("CLS-27", "Turkish Airlines A.Ş., İstanbul Havalimanı, İstanbul", "TR", "Classification"),
    ("CLS-28", "Tata Consultancy Services Limited, Mumbai, Maharashtra", "IN", "Classification"),
    ("CLS-29", "Emirates PJSC, Dubai, UAE", "AE", "Classification"),
    ("CLS-30", "Qatar Airways QPSC, Doha, Qatar", "QA", "Classification"),
    ("CLS-31", "EDP SA, Avenida 24 de Julho, Lisboa", "PT", "Classification"),
    ("CLS-32", "Galp Energia SGPS SA, Rua Tomás da Fonseca, Lisboa", "PT", "Classification"),
    ("CLS-33", "Petrobras SA, Av. República do Chile, Rio de Janeiro", "BR", "Classification"),
    ("CLS-34", "MercadoLibre SRL, Av. del Libertador, Buenos Aires", "AR", "Classification"),
    ("CLS-35", "TechCo SRL, Av. Corrientes, Buenos Aires", "AR", "Classification"),
    ("CLS-36", "Naspers Limited, 40 Heerengracht, Cape Town", "ZA", "Classification"),
    ("CLS-37", "Standard Bank (Pty) Ltd, 5 Simmonds St, Johannesburg", "ZA", "Classification"),
    ("CLS-38", "PLDT Corp, Makati City, Philippines", "PH", "Classification"),
    ("CLS-39", "Petronas Berhad, Kuala Lumpur, Malaysia", "MY", "Classification"),
    ("CLS-40", "PT Astra International Tbk, Jakarta, Indonesia", "ID", "Classification"),
    ("CLS-41", "VinFast CTCP, Hai Phong, Vietnam", "VN", "Classification"),
    ("CLS-42", "Dangote Plc, Lagos, Nigeria", "NG", "Classification"),
    ("CLS-43", "Safaricom Plc, Nairobi, Kenya", "KE", "Classification"),
    ("CLS-44", "TBL Ltd, Dar es Salaam, Tanzania", "TZ", "Classification"),
    ("CLS-45", "CRDB Bank Plc, Dar es Salaam, Tanzania", "TZ", "Classification"),
    ("CLS-46", "Commercial International Bank S.A.E., Cairo, Egypt", "EG", "Classification"),
    ("CLS-47", "Attijariwafa Bank S.A., Casablanca, Morocco", "MA", "Classification"),
    ("CLS-48", "SoftBank Group KK, Tokyo, Japan", "JP", "Classification"),
    ("CLS-49", "Samsung Electronics 주식회사, Seoul, Korea", "KR", "Classification"),
    ("CLS-50", "DMM.com GK, Tokyo, Japan", "JP", "Classification")
  )

  // Ambiguity group definitions
  val ambiguityGroups: Map[String, Set[String]] = Map(
    "id_my" -> Set("ID", "MY"),           // Indonesia vs Malaysia
    "es_mx_ar" -> Set("ES", "MX", "AR", "CL", "CO", "PE"), // Spanish-speaking
    "en_us_gb_au_ca_sg" -> Set("US", "GB", "AU", "CA", "SG", "NZ", "IE"), // English-speaking
    "pt_br" -> Set("PT", "BR"),             // Portuguese-speaking
    "ar_sa_ae_qa_kw_eg" -> Set("SA", "AE", "QA", "KW", "EG", "MA", "DZ", "TN", "LY", "IQ", "SY", "JO", "LB", "PS", "BH", "OM", "YE"), // Arabic-speaking
    "zh_cn_hk_mo_tw_sg" -> Set("CN", "HK", "MO", "TW", "SG", "MY") // Chinese-speaking
  )

  def detectGroup(cc: String): String = {
    ambiguityGroups.find(_._2.contains(cc)).map(_._1).getOrElse("other")
  }

  // Run evaluation
  def runEvaluation(useAdjudication: Boolean): Map[String, (Int, Int, Double)] = {
    val adjudicator = if (useAdjudication) CountryAdjudicator.withSimulatedGemma() else CountryAdjudicator.rulesOnly()
    val results = scala.collection.mutable.Map[String, (Int, Int)]() // group -> (correct, total)
    var totalCorrect = 0
    var totalCount = 0

    testCases.foreach { case (id, input, expected, category) =>
      val stage1 = CountryDetector.extractCandidates(input, gazetteer)
      val stage2Opt = if (useAdjudication) adjudicator.adjudicate(stage1) else None

      val finalCountry = stage2Opt.flatMap(_.chosenCountry).orElse(stage1.top1.map(_.countryCode))
      val isCorrect = finalCountry.contains(expected)

      val group = detectGroup(expected)
      val (correct, count) = results.getOrElse(group, (0, 0))
      results(group) = (correct + (if (isCorrect) 1 else 0), count + 1)
      if (isCorrect) totalCorrect += 1
      totalCount += 1

      val (c2, c3) = results.getOrElse("overall", (0, 0))
      results("overall") = (c2 + (if (isCorrect) 1 else 0), c3 + 1)

      // Also track per-category
      val (cc, ct) = results.getOrElse(s"cat:$category", (0, 0))
      results(s"cat:$category") = (cc + (if (isCorrect) 1 else 0), ct + 1)
    }

    results.map { case (k, (c, t)) => k -> (c, t, c.toDouble / t) }.toMap
  }

  println("=" * 80)
  println("COUNTRY DETECTION EVALUATION")
  println("=" * 80)
  println(s"Test cases: ${testCases.size}")
  println(s"Gazetteer: ${gazetteer.cities.size} cities, ${gazetteer.admin1s.size} admin1s")
  println()

  // Baseline: Stage 1 only
  println("--- BASELINE: Stage 1 (Deterministic Signals + Gazetteer) ---")
  val baselineResults = runEvaluation(useAdjudication = false)
  baselineResults.toList.sortBy(-_._2._3).foreach { case (group, (correct, total, acc)) =>
    println(f"  $group%-25s: $correct%3d / $total%-3d = $acc%.1f%%")
  }
  println()

  // With adjudication
  println("--- WITH ADJUDICATION: Stage 1 + Stage 2 (Simulated Gemma) ---")
  val adjudicatedResults = runEvaluation(useAdjudication = true)
  adjudicatedResults.toList.sortBy(-_._2._3).foreach { case (group, (correct, total, acc)) =>
    val baselineAcc = baselineResults.get(group).map(_._3).getOrElse(0.0)
    val diff = acc - baselineAcc
    val sign = if (diff >= 0) "+" else ""
    println(f"  $group%-25s: $correct%3d / $total%-3d = $acc%.1f%%  (baseline: $baselineAcc%.1f%%  ${sign}$diff%.1f%%)")
  }
  println()

  // Summary table for ambiguity groups
  println("--- AMBIGUITY GROUP COMPARISON ---")
  println(f"${"Group"}%-25s ${"Baseline"}%-12s ${"+Adjudication"}%-14s ${"Delta"}%-8s ${"Count"}")
  println("-" * 70)
  val groups = List("id_my", "es_mx_ar", "en_us_gb_au_ca_sg", "pt_br", "ar_sa_ae_qa_kw_eg", "zh_cn_hk_mo_tw_sg", "other")
  groups.foreach { g =>
    val b = baselineResults.getOrElse(g, (0, 0, 0.0))
    val a = adjudicatedResults.getOrElse(g, (0, 0, 0.0))
    if (b._2 > 0 || a._2 > 0) {
      println(f"$g%-25s ${b._3*100}%5.1f%%      ${a._3*100}%5.1f%%          ${a._3 - b._3}%+5.1f%%  ${a._2}")
    }
  }
  println("-" * 70)
  val bAll = baselineResults("overall")
  val aAll = adjudicatedResults("overall")
  println(f"${"OVERALL"}%-25s ${bAll._3*100}%5.1f%%      ${aAll._3*100}%5.1f%%          ${aAll._3 - bAll._3}%+5.1f%%  ${aAll._2}")
  println()

  // Per-category breakdown
  println("--- PER-CATEGORY BREAKDOWN (With Adjudication) ---")
  List("Address", "PersonName", "BankAccount", "CompanyName", "Classification").foreach { cat =>
    val key = s"cat:$cat"
    adjudicatedResults.get(key).foreach { case (c, t, acc) =>
      val b = baselineResults.get(key).map(_._3).getOrElse(0.0)
      println(f"  $cat%-15s: $c%3d / $t%-3d = $acc%.1f%%  (baseline: ${b*100}%.1f%%)")
    }
  }

  println()
  println("=" * 80)
  println("EVALUATION COMPLETE")
  println("=" * 80)
}