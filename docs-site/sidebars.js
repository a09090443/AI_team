import { categories, docs } from './catalog.mjs';

// 依 catalog.mjs 的分類產生 sidebar；歷史分類預設收合，避免被當成現行文件。
const sidebars = {
  docs: categories.map((category) => ({
    type: 'category',
    label: category.label,
    collapsed: category.id === 'history',
    items: docs
      .filter((doc) => doc.category === category.id)
      .map((doc) => ({ type: 'doc', id: doc.id, label: doc.title })),
  })),
};

export default sidebars;
