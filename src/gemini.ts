import type { Extracted } from './types';

const API_KEY = process.env.EXPO_PUBLIC_GEMINI_API_KEY ?? '';
const MODEL = process.env.EXPO_PUBLIC_GEMINI_MODEL || 'gemini-3.5-flash-lite';

const PROMPT = `너는 스마트폰 스크린샷에서 "나중에 가 보고 싶은 장소"를 뽑아내는 도우미야.
이미지(인스타그램 게시물, 지도 앱, 블로그, 팝업스토어·전시 포스터 등)를 보고 아래 JSON만 출력해.
- isPlace: 특정 오프라인 장소(가게, 카페, 식당, 전시, 팝업, 관광지 등)가 보이면 true
- name: 가게/장소 이름 (지점명 포함, 모르면 빈 문자열)
- address: 이미지에 보이는 주소나 위치 단서 (예: "서울 성동구 연무장길 12", "성수동"). 없으면 빈 문자열
- category: 카페, 식당, 술집, 디저트, 전시, 팝업, 쇼핑, 관광, 기타 중 하나
- note: 사용자가 왜 저장했을지 한 문장 (예: "소금빵 맛집", "10월까지 하는 사진전")
- endDate: 운영 종료일이 보이면 YYYY-MM-DD, 아니면 null
추측으로 주소를 지어내지 마.`;

const SCHEMA = {
  type: 'OBJECT',
  properties: {
    isPlace: { type: 'BOOLEAN' },
    name: { type: 'STRING' },
    address: { type: 'STRING' },
    category: { type: 'STRING' },
    note: { type: 'STRING' },
    endDate: { type: 'STRING', nullable: true },
  },
  required: ['isPlace', 'name', 'address', 'category', 'note'],
};

export function hasGeminiKey(): boolean {
  return API_KEY.length > 0;
}

export async function extractPlace(base64Jpeg: string): Promise<Extracted> {
  if (!API_KEY) throw new Error('Gemini API 키가 없어요. .env의 EXPO_PUBLIC_GEMINI_API_KEY를 설정하세요.');
  const res = await fetch(
    `https://generativelanguage.googleapis.com/v1beta/models/${MODEL}:generateContent`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'x-goog-api-key': API_KEY },
      body: JSON.stringify({
        contents: [
          {
            role: 'user',
            parts: [{ text: PROMPT }, { inline_data: { mime_type: 'image/jpeg', data: base64Jpeg } }],
          },
        ],
        generationConfig: {
          temperature: 0,
          responseMimeType: 'application/json',
          responseSchema: SCHEMA,
        },
      }),
    },
  );
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`Gemini 요청 실패 (${res.status}): ${text.slice(0, 200)}`);
  }
  const json = await res.json();
  const text: string | undefined = json?.candidates?.[0]?.content?.parts?.[0]?.text;
  if (!text) throw new Error('Gemini 응답이 비어 있어요.');
  const parsed = JSON.parse(text) as Partial<Extracted>;
  const endDate = parsed.endDate && /^\d{4}-\d{2}-\d{2}$/.test(parsed.endDate) ? parsed.endDate : null;
  return {
    isPlace: Boolean(parsed.isPlace),
    name: (parsed.name ?? '').trim(),
    address: (parsed.address ?? '').trim(),
    category: (parsed.category ?? '기타').trim() || '기타',
    note: (parsed.note ?? '').trim(),
    endDate,
  };
}
