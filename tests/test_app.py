import io
import tempfile
import unittest

from rezhim.app import create_app, number

HEADERS={'X-Rezhim-Client':'desk'}


class AppTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.app=create_app(self.tmp.name)
        self.client=self.app.test_client()
        self.post('/grades',{'name':'ТЕСТ — НЕ ПРОИЗВОДСТВЕННЫЕ ДАННЫЕ'})
        self.source=self.upload(b'%PDF-1.4\nsynthetic test fixture')['id']
        self.base={'grade_id':1,'line':'A','timestamp':'2026-09-20T16:19','a103':'running','ab124':'stopped','suutp':'unknown',
                   'steady':True,'doc_id':None,'recipe12':'test-1','recipe3':'test-2','source_id':self.source,'pumps':{'1':2,'2':4,'3':1}}

    def tearDown(self):
        self.tmp.cleanup()

    def post(self,path,data,code=200):
        response=self.client.post('/api'+path,json=data,headers=HEADERS)
        self.assertEqual(response.status_code,code,response.get_data(as_text=True))
        return response.get_json()

    def upload(self,content):
        response=self.client.post('/api/sources',data={'file':(io.BytesIO(content),'test.pdf')},headers=HEADERS)
        self.assertEqual(response.status_code,200)
        return response.get_json()

    def save(self,values,code=200,**kwargs):
        return self.post('/observations',{**self.base,'values':values,**kwargs},code)

    def value(self,key='production',num='20,5',status='confirmed'):
        return {'param_key':key,'number':num,'status':status}

    def state(self):
        return self.client.get('/api/state').get_json()

    def test_missing_zero_error_and_numbers(self):
        for value in (None,'','  '): self.assertIsNone(number(value))
        self.assertEqual(number('0'),0)
        self.assertEqual(number('-0,5'),-.5)
        for value in (True,False,'NaN','inf','12abc',[],float('nan')):
            with self.assertRaises(ValueError): number(value)
        self.save([self.value(num=''),self.value('peak1','0'),{**self.value('peak2',None),'state':'error'}])
        values=self.state()['measurement']
        self.assertEqual([(v['state'],v['number']) for v in values],[('missing',None),('number',0),('error',None)])
        self.assertEqual(len(self.post('/export',{})['data']['measurement']),1)

    def test_merge_is_idempotent_and_preserves_sources(self):
        self.save([self.value()])
        source2=self.upload(b'%PDF-1.4\nsecond source')['id']
        self.save([self.value(),self.value('peak1',200)],source_id=source2)
        self.save([self.value(),self.value('peak1',200)],source_id=source2)
        state=self.state()
        self.assertEqual(len(state['observation']),1)
        self.assertEqual(len(state['measurement']),2)
        self.assertEqual(len(state['value_source']),3)

    def test_conflict_excluded_until_explicit_review(self):
        self.save([self.value()])
        result=self.save([self.value(num=21)])
        self.assertEqual(result['conflicts'],1)
        self.assertEqual(self.post('/export',{})['data']['measurement'],[])
        candidates=self.state()['measurement']
        self.post('/values/'+str(candidates[1]['id'])+'/review',{'status':'confirmed'})
        package=self.post('/export',{})
        self.assertEqual(package['data']['measurement'][0]['number'],21)
        self.assertEqual(self.state()['measurement'][0]['status'],'rejected')

    def test_unknown_configuration_never_defaulted(self):
        self.save([self.value()],a103='unknown',ab124='unknown')
        obs=self.state()['observation'][0]
        self.assertEqual(obs['a103'],'unknown')
        self.save([self.value()],400,a103='running',ab124='unknown')

    def test_atomic_rollback_and_lines(self):
        self.save([self.value(),self.value('not_a_parameter',1)],400)
        self.assertEqual(self.state()['observation'],[])
        self.save([self.value()])
        self.save([self.value()],line='B')
        self.assertEqual(len(self.state()['observation']),2)
        self.save([self.value()],400,recipe12='different')

    def test_pumps_and_units(self):
        self.save([self.value()],400,pumps={'1':2,'2':2})
        self.save([self.value('pal',2)],400)
        self.assertEqual(self.state()['observation'],[])

    def test_units_can_be_defined_after_missing_value_but_not_changed_after_number(self):
        self.save([self.value('pal',None)])
        param=next(p for p in self.state()['param'] if p['key']=='pal')
        self.post('/params',{**param,'unit':'TEST-unit'})
        self.save([self.value('pal',2)])
        self.post('/params',{**param,'unit':'other'},400)

    def test_docs_dates_and_norms(self):
        doc={'grade_id':1,'version':'test-v1','valid_from':'2026-01-01','valid_to':'2026-12-31','source_id':self.source,
             'norms':[{'param_key':'production','lower_value':10,'target':20,'upper_value':30,'section':'TEST','row_number':'1'}]}
        id=self.post('/docs',doc)['id']
        self.save([self.value()],doc_id=id)
        self.save([self.value()],400,doc_id=id,timestamp='2025-12-31T12:00')
        self.post('/docs',{**doc,'version':'overlap'},400)
        self.assertEqual(len(self.state()['doc_version']),1)

    def test_review_does_not_confirm_missing(self):
        self.save([self.value(num=None)])
        self.post('/values/1/review',{'status':'confirmed'},400)

    def test_source_hash_dedup_and_write_guard(self):
        self.assertEqual(self.upload(b'%PDF-1.4\nsynthetic test fixture')['id'],self.source)
        response=self.client.post('/api/grades',json={'name':'bad'})
        self.assertEqual(response.status_code,403)
        response=self.client.post('/api/grades',json={'name':'bad'},headers={**HEADERS,'Origin':'https://other.example'})
        self.assertEqual(response.status_code,403)

    def test_export_versions_and_no_photos(self):
        self.save([self.value(),self.value('peak1',200,'review')])
        first=self.post('/export',{});second=self.post('/export',{})
        self.assertEqual(second['version'],first['version']+1)
        self.assertEqual(first['counts']['values'],1)
        self.assertNotIn('path',first['data']['source'][0])

    def test_mobile_assets_local(self):
        for url in ('/','/mobile/','/mobile/sw.js','/mobile/app.mjs','/mobile/domain.mjs','/mobile/style.css','/mobile/manifest.webmanifest','/mobile/icon.svg'):
            response=self.client.get(url)
            self.assertEqual(response.status_code,200,url)
            response.close()


if __name__=='__main__': unittest.main()
