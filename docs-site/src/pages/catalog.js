import Link from '@docusaurus/Link';
import useBaseUrl from '@docusaurus/useBaseUrl';
import Layout from '@theme/Layout';
import Heading from '@theme/Heading';
import { categories, docs, statuses } from '../../catalog.mjs';
import styles from './catalog.module.css';

export default function Catalog() {
  const llmsUrl = useBaseUrl('/llms.txt');
  return (
    <Layout title="文件目錄" description="docs-site/docs/ 所有文件的分類、狀態與用途">
      <main className={styles.catalog}>
        <p className={styles.eyebrow}>DOCUMENT CATALOG</p>
        <Heading as="h1">文件目錄</Heading>
        <p className={styles.intro}>
          docs-site/docs/ 內每份文件的分類、狀態與一句話用途。資料來源是 <code>docs-site/catalog.mjs</code>，
          機器可讀版本為 <a href={llmsUrl}>llms.txt</a>。第一次接觸專案請先讀{' '}
          <Link to="/agent/">AI Agent 入口</Link>。
        </p>
        <dl className={styles.legend}>
          {Object.entries(statuses).map(([key, status]) => (
            <div key={key}>
              <dt><span className={`${styles.badge} ${styles[key]}`}>{status.label}</span></dt>
              <dd>{status.description}</dd>
            </div>
          ))}
        </dl>
        {categories.map((category) => (
          <section key={category.id} className={styles.category}>
            <Heading as="h2" id={category.id}>{category.label}</Heading>
            <p className={styles.categoryDescription}>{category.description}</p>
            <ul className={styles.list}>
              {docs.filter((doc) => doc.category === category.id).map((doc) => (
                <li key={doc.id} className={styles.item}>
                  <div className={styles.itemHead}>
                    <Link to={`/docs/${doc.id}`}>{doc.title}</Link>
                    <span className={`${styles.badge} ${styles[doc.status]}`}>{statuses[doc.status].label}</span>
                    <code className={styles.path}>docs-site/docs/{doc.id}.md</code>
                  </div>
                  <p>{doc.summary}</p>
                  {doc.readWhen && <p className={styles.when}><strong>何時讀：</strong>{doc.readWhen}</p>}
                  {doc.code?.length > 0 && (
                    <p className={styles.code}>
                      <strong>相關程式：</strong>
                      {doc.code.map((c) => <code key={c}>{c}</code>)}
                    </p>
                  )}
                </li>
              ))}
            </ul>
          </section>
        ))}
      </main>
    </Layout>
  );
}
