import { categories, docs } from './catalog.mjs';

// 依 catalog.mjs 的分類產生 sidebar；整個分類都是歷史文件時預設收合，避免被當成現行文件。
const sidebars = {
  docs: categories.map((category) => ({
    type: 'category',
    label: category.label,
    collapsed: docs.filter((doc) => doc.category === category.id).every((doc) => doc.status === 'historical'),
    items: docs
      .filter((doc) => doc.category === category.id)
      .map((doc) => ({ type: 'doc', id: doc.id, label: doc.title })),
  })),
};

export default sidebars;
