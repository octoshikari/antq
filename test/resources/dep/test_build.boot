(def foo ::foo)

(set-env!
  :dependencies '[[foo/core "1.0.0"]
                  [bar "2.0.0"]
                  [baz "3.0.0" :scope "test"]
                  ^:foo/bar [with/meta "4.0.0"]
                  [ver-not-string :version]
                  [ver-empty ""]
                  ;; should be ignored
                  ^:antq/exclude [meta/ignore "5.0.0"]])

(set-env!
  :repositories #(conj % '["antq-test" {:url "s3://antq-repo/"}]))

(def bar ::bar)
