import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import { en } from './locales/en';
import { ar } from './locales/ar';

/**
 * i18n bootstrap. The initial language comes from the server-resolved profile
 * (database-backed); until that loads the browser locale decides. The whole
 * app is RTL-capable - Ant Design handles `dir="rtl"` at the ConfigProvider
 * level once the language switches AND Artificial the persisted choice.
 */
export const supportedLanguages = [
  { code: 'en', label: 'English', dir: 'ltr' },
  { code: 'ar', label: 'العربية', dir: 'rtl' },
] as const;

export type LanguageCode = (typeof supportedLanguages)[number]['code'];

i18n.use(initReactI18next).init({
  resources: {
    en: { translation: en },
    ar: { translation: ar },
  },
  lng: localStorage.getItem('wfe:locale') ?? 'en',
  fallbackLng: 'en',
  interpolation: { escapeValue: false },
});

export function setLocale(code: LanguageCode) {
  localStorage.setItem('wfe:locale', code);
  document.documentElement.lang = code;
  document.documentElement.dir = supportedLanguages.find((l) => l.code === code)?.dir ?? 'ltr';
  void i18n.changeLanguage(code);
}

export default i18n;