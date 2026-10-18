plugins { id("org.jetbrains.kotlin.jvm") }

// 시험용 PDF(TestPdf) · 압축(StoredArchives)을 짓는 도구. 0.54.6 까지는 :document 의 test fixtures 였는데, :document 가
// 멀티플랫폼이 되며(iOS 공유) java-test-fixtures 를 쓸 수 없어 따로 뺐다. 시험에서만 쓴다.
dependencies { implementation(project(":document")) }

kotlin { jvmToolchain(21) }
