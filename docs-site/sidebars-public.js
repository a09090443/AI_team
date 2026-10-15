import {manual} from './public-manual.mjs';
export default {
  publicDocs: [...new Set(manual.map(p => p.group))].map(label => ({
    type: 'category', label, collapsed: false,
    items: manual.filter(p => p.group === label).map(p => p.id),
  })),
};
