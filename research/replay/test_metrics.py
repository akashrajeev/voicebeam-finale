import unittest
from metrics import metrics
class Tests(unittest.TestCase):
    def test_known_states_and_ratio(self):
        rows=[{'time_ms':t,'gate':'TARGET' if t>=100 else 'OTHER'} for t in range(0,500,10)]
        labels=[{'name':'target','start_ms':0,'end_ms':500,'expected':['TARGET'],'switch':True}]
        d=[{'gate':'TARGET','rawRms':.1,'outputRms':.05}]*3
        r=metrics(rows,labels,d);self.assertEqual(r['wrong_state_pct'],20)
        self.assertEqual(r['switches'][0]['switch_latency_ms'],100)
        self.assertAlmostEqual(r['digital_sampled_ratios']['TARGET']['digital_output_vs_raw_db'],-6.0206,places=4)
    def test_missing_latency_and_invalid_energy(self):
        r=metrics([{'time_ms':0,'gate':'OTHER'}],[{'name':'target','start_ms':0,'end_ms':500,'expected':['TARGET'],'switch':True}],[])
        self.assertIsNone(r['switches'][0]['switch_latency_ms']);self.assertIsNone(r['digital_sampled_ratios']['OTHER']['digital_output_vs_raw_db'])
    def test_excluded_calibration(self):
        r=metrics([{'time_ms':0,'gate':'UNLOCKED'}],[{'name':'cal','start_ms':0,'end_ms':10,'expected':[]}],[])
        self.assertIsNone(r['wrong_state_pct'])
if __name__=='__main__':unittest.main()
